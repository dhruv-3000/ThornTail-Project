/*
 * Copyright 2018 Red Hat, Inc, and individual contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wildfly.swarm.plugin.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Stack;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyArtifact;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency;
import org.gradle.api.internal.project.DefaultProjectAccessListener;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.initialization.ProjectAccessListener;
import org.wildfly.swarm.fractions.FractionDescriptor;
import org.wildfly.swarm.fractions.FractionList;
import org.wildfly.swarm.tools.ArtifactSpec;

/**
 * The {@code GradleDependencyResolutionHelper} helps with resolving and translating a project's dependencies in to various
 * forms that are usable by the Thorntail tooling for Gradle.
 */
@SuppressWarnings("UnstableApiUsage")
public final class GradleDependencyResolutionHelper {

    private static String pluginVersion;

    private GradleDependencyResolutionHelper() {
    }

    /**
     * Parse the plugin definition file and extract the version details from it.
     */
    public static String determinePluginVersion() {
        if (pluginVersion == null) {
            final String fileName = "META-INF/gradle-plugins/thorntail.properties";
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            String version;
            try (InputStream stream = loader.getResourceAsStream(fileName)) {
                Properties props = new Properties();
                props.load(stream);
                version = props.getProperty("implementation-version");
            } catch (IOException e) {
                throw new IllegalStateException("Unable to locate file: " + fileName, e);
            }
            pluginVersion = version;
        }
        return pluginVersion;
    }


    /**
     * Convenience method to determine if the given dependency descriptor represents an internal Gradle project or not.
     *
     * @param project    the Gradle project reference.
     * @param descriptor the dependency descriptor.
     * @return true if the descriptor represents a Gradle project, false otherwise.
     */
    public static boolean isProject(Project project, DependencyDescriptor descriptor) {
        final String specGAV = String.format(GROUP_ARTIFACT_VERSION_FORMAT, descriptor.getGroup(), descriptor.getName(),
                descriptor.getVersion());
        return getAllProjects(project).containsKey(specGAV) || getIncludedProjectIdentifiers(project).contains(specGAV);
    }

    /**
     * Resolve the given artifact specifications.
     *
     * @param project         the Gradle project reference.
     * @param specs           the specifications that need to be resolved.
     * @param transitive      should the artifacts be resolved transitively?
     * @param excludeDefaults should we skip resolving artifacts that belong to the Thorntail group?
     * @return collection of resolved artifact specifications.
     */
    public static Set<ArtifactSpec> resolveArtifacts(Project project, Collection<ArtifactSpec> specs, boolean transitive,
                                                     boolean excludeDefaults) {
        if (project == null) {
            throw new IllegalArgumentException("Gradle project reference cannot be null.");
        }
        if (specs == null) {
            project.getLogger().warn("Artifact specification collection is null.");
            return Collections.emptySet();
        }

        // Early return if there is nothing to resolve.
        if (specs.isEmpty()) {
            return Collections.emptySet();
        }

        final Configuration config = project.getConfigurations().detachedConfiguration().setTransitive(transitive);
        final DependencySet dependencySet = config.getDependencies();
        final Map<String, Project> projectGAVCoordinates = getAllProjects(project);
        final ProjectAccessListener listener = new DefaultProjectAccessListener();

        Set<ArtifactSpec> result = new HashSet<>();
        specs.forEach(s -> {
            // 1. Do we need to resolve this entry?
            final String specGAV = String.format(GROUP_ARTIFACT_VERSION_FORMAT, s.groupId(), s.artifactId(), s.version());
            boolean resolved = s.file != null;
            boolean projectEntry = projectGAVCoordinates.containsKey(specGAV);

            // 2. Should we skip this spec?
            if (excludeDefaults && FractionDescriptor.THORNTAIL_GROUP_ID.equals(s.groupId()) && !projectEntry) {
                return;
            }

            // 3. Should this entry be resolved?
            if (!resolved || transitive) {
                // a.) Does this entry represent a project dependency?
                if (projectGAVCoordinates.containsKey(specGAV)) {
                    dependencySet.add(new DefaultProjectDependency((ProjectInternal) projectGAVCoordinates.get(specGAV), listener, false));
                } else {
                    DefaultExternalModuleDependency d = new DefaultExternalModuleDependency(s.groupId(), s.artifactId(), s.version());
                    DefaultDependencyArtifact da = new DefaultDependencyArtifact(s.artifactId(), s.type(), s.type(), s.classifier(), null);
                    d.addArtifact(da);
                    dependencySet.add(d);
                }
            } else {
                // 4. Nothing else to do, just add the spec to the result.
                result.add(s);
            }
        });

        // 5. Are there any specs that need resolution?
        if (!dependencySet.isEmpty()) {
            config.getResolvedConfiguration().getResolvedArtifacts().stream()
                    .map(ra -> asDescriptor("compile", ra).toArtifactSpec())
                    .forEach(result::add);
        }
        return result;
    }

    /**
     * Determine the dependencies associated with a Gradle project. This method returns a Map whose key represents a top level
     * dependency associated with this project and the value represents a collection of dependencies that the "key" requires.
     *
     * @param project                     the Gradle project reference.
     * @param configuration               the dependency configuration that needs to be resolved.
     * @param resolveChildrenTransitively if set to true, then upstream dependencies will be resolved transitively.
     * @return the dependencies associated with the Gradle project for the specified configuration.
     */
    public static Map<DependencyDescriptor, Set<DependencyDescriptor>>
    determineProjectDependencies(Project project, String configuration, boolean resolveChildrenTransitively) {
        if (project == null) {
            throw new IllegalArgumentException("Gradle project reference cannot be null.");
        }
        project.getLogger().info("Requesting dependencies for configuration: {}", configuration);
        Configuration requestedConfiguration = project.getConfigurations().findByName(configuration);
        if (requestedConfiguration == null) {
            project.getLogger().warn("Unable to locate dependency configuration with name: {}", configuration);
            return Collections.emptyMap();
        }

        //
        // Step 1
        // ------
        // Iterate through the hierarchy of the given configuration and determine the correct scope of all
        // "top-level" dependencies.
        //
        Map<String, String> dependencyScopeMap = new HashMap<>();

        // In case of custom configurations, we will assign the scope to what has been requested
        String defaultScopeForUnknownConfigurations =
                REMAPPED_SCOPES.computeIfAbsent(requestedConfiguration.getName(), cfgName -> {
                    throw new IllegalStateException("Unknown configuration name provided: " + cfgName);
                });
        requestedConfiguration.getHierarchy().forEach(cfg -> {
            cfg.getDependencies().forEach(dep -> {
                String key = String.format(GROUP_ARTIFACT_FORMAT, dep.getGroup(), dep.getName());
                dependencyScopeMap.put(key, REMAPPED_SCOPES.getOrDefault(cfg.getName(), defaultScopeForUnknownConfigurations));
            });
        });

        //
        // Step 2
        // ------
        // Assuming that the given configuration can be resolved, get the resolved artifacts and populate the return Map.
        //
        Set<String> availableFractions = FractionList.get().getFractionDescriptors()
                .stream()
                .map(d -> String.format(GROUP_ARTIFACT_FORMAT, d.getGroupId(), d.getArtifactId()))
                .collect(Collectors.toSet());

        ResolvedConfiguration resolvedConfig = requestedConfiguration.getResolvedConfiguration();
        Map<DependencyDescriptor, Set<DependencyDescriptor>> dependencyMap = new HashMap<>();
        resolvedConfig.getFirstLevelModuleDependencies().forEach(resolvedDep -> {
            String lookup = String.format(GROUP_ARTIFACT_FORMAT, resolvedDep.getModuleGroup(), resolvedDep.getModuleName());
            String scope = dependencyScopeMap.get(lookup);
            if (scope == null) {
                // Should never happen.
                throw new IllegalStateException("Gradle dependency resolution logic is broken. Unable to get scope for dependency: " + lookup);
            }
            if ("import".equals(scope) || resolvedDep.getModuleArtifacts().isEmpty()) {
                return;
            }
            if (resolveChildrenTransitively) {
                resolveDependencies(availableFractions, scope, resolvedDep, resolvedDep, dependencyMap);
            } else {
                DependencyDescriptor key = asDescriptor(scope, resolvedDep);
                Set<DependencyDescriptor> value = resolvedDep.getChildren()
                        .stream()
                        .filter(rd -> !rd.getModuleArtifacts().isEmpty())
                        .map(rd -> asDescriptor(scope, rd))
                        .collect(Collectors.toSet());
                dependencyMap.put(key, value);
            }
        });

        printDependencyMap(dependencyMap, project);
        return dependencyMap;
    }

    /**
     * Traverse the dependency tree and resolve the dependencies that will be associated with the given {@code root}
     * dependency. This method will move the entire dependency tree for known fractions to the top level so that the
     * build tool can do its packaging piece properly.
     *
     * @param knownFractions the list of known fractions. This collection should represent each fraction by its
     *                       GAV coordinates in the form {@code group:artifact}
     * @param scope          the scope associated with the {@code root} dependency.
     * @param root           the root dependency, which effectively translates to the key in the dependency map.
     * @param node           the current node (under the root dependency) that is being resolved.
     * @param depsMaps       the dependency map that needs to be populated with the child dependencies.
     */
    private static void resolveDependencies(Collection<String> knownFractions, String scope, ResolvedDependency root,
                                            ResolvedDependency node, Map<DependencyDescriptor, Set<DependencyDescriptor>> depsMaps) {

        String key = String.format(GROUP_ARTIFACT_FORMAT, node.getModuleGroup(), node.getModuleName());
        DependencyDescriptor descriptor = asDescriptor(scope, node);
        if (depsMaps.containsKey(descriptor)) {
            return;
        }
        if (knownFractions.contains(key)) {
            depsMaps.put(descriptor, getDependenciesTransitively(scope, node));
            return;
        }
        if (!node.getModuleArtifacts().isEmpty()) {
            depsMaps.computeIfAbsent(asDescriptor(scope, root), __ -> new HashSet<>(3)).add(descriptor);
        }
        node.getChildren().forEach(rd -> resolveDependencies(knownFractions, scope, root, rd, depsMaps));
    }

    /**
     * Get the dependencies (transitively) of the given parent element.
     *
     * @param scope  the scope to use for the parent.
     * @param parent the parent dependency.
     * @return a collection of all dependencies of the given parent.
     */
    private static Set<DependencyDescriptor> getDependenciesTransitively(String scope, ResolvedDependency parent) {
        Stack<ResolvedDependency> stack = new Stack<>();
        stack.push(parent);
        Set<DependencyDescriptor> dependencies = new HashSet<>();
        while (!stack.empty()) {
            ResolvedDependency rd = stack.pop();
            // Skip the parent's artifacts.
            if (rd != parent) {
                rd.getModuleArtifacts().forEach(a -> dependencies.add(asDescriptor(scope, a)));
            }
            rd.getChildren().forEach(d -> {
                if (!stack.contains(d)) {
                    stack.add(d);
                }
            });
        }
        return dependencies;
    }

    /**
     * Translate the given {@link ResolvedDependency resolved dependency} in to a {@link DependencyDescriptor} reference.
     *
     * @param scope      the scope to assign to the descriptor.
     * @param dependency the resolved dependency reference.
     * @return an instance of {@link DependencyDescriptor}.
     */
    private static DependencyDescriptor asDescriptor(String scope, ResolvedDependency dependency) {

        Set<ResolvedArtifact> artifacts = dependency.getModuleArtifacts();

        // Let us use the first artifact's type for determining the type.
        // I am not sure under what circumstances, would we need to check for multiple artifacts.
        String type = "jar";
        String classifier = null;
        File file = null;

        if (!artifacts.isEmpty()) {
            ResolvedArtifact ra = artifacts.iterator().next();
            type = ra.getType();
            classifier = ra.getClassifier();
            file = ra.getFile();
        }
        return new DefaultDependencyDescriptor(scope, dependency.getModuleGroup(), dependency.getModuleName(),
                                               dependency.getModuleVersion(), type, classifier, file);
    }

    /**
     * Translate the given {@link ResolvedArtifact resolved artifact} in to a {@link DependencyDescriptor} reference.
     *
     * @param scope    the scope to assign to the descriptor.
     * @param artifact the resolved artifact reference.
     * @return an instance of {@link DependencyDescriptor}.
     */
    private static DependencyDescriptor asDescriptor(String scope, ResolvedArtifact artifact) {
        ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
        return new DefaultDependencyDescriptor(scope, id.getGroup(), id.getName(), id.getVersion(),
                                               artifact.getType(), artifact.getClassifier(), artifact.getFile());
    }

    /**
     * Temp method for printing out the dependency map.
     */
    private static void printDependencyMap(Map<DependencyDescriptor, Set<DependencyDescriptor>> map, Project project) {
        final String NEW_LINE = "\n";
        if (project.getLogger().isEnabled(LogLevel.INFO)) {
            StringBuilder builder = new StringBuilder(100);
            builder.append("Resolved dependencies:").append(NEW_LINE);
            map.forEach((k, v) -> {
                builder.append(k).append(NEW_LINE);
                v.forEach(e -> builder.append("\t").append(e).append(NEW_LINE));
                builder.append(NEW_LINE);
            });
            project.getLogger().info(builder.toString());
            // displayMessage(builder.toString());
        }
    }

    /**
     * Get the collection of Gradle projects along with their GAV definitions. This collection is used for determining if an
     * artifact specification represents a Gradle project or not.
     *
     * @param project the Gradle project that is being analyzed.
     * @return a map of GAV coordinates for each of the available projects (returned as keys).
     */
    private static Map<String, Project> getAllProjects(final Project project) {
        return getCachedReference(project, "thorntail_project_gav_collection", () -> {
            Map<String, Project> gavMap = new HashMap<>();
            project.getRootProject().getAllprojects().forEach(p -> {
                gavMap.put(p.getGroup() + ":" + p.getName() + ":" + p.getVersion(), p);
            });
            return gavMap;
        });
    }

    /**
     * Attempt to load the project identifiers (group:artifact) for projects that have been included. This method isn't
     * guaranteed to work all the time since there is no good API that we can use and need to rely on reflection for now.
     *
     * @param project the project reference.
     * @return a collection of "included" project identifiers (a.k.a., composite projects).
     */
    private static Set<String> getIncludedProjectIdentifiers(final Project project) {
        return getCachedReference(project, "thorntail_included_project_identifiers", () -> {
            Set<String> identifiers = new HashSet<>();

            // Check for included builds as well.
            project.getGradle().getIncludedBuilds().forEach(build -> {
                // Determine if the given reference has the following method definition,
                // org.gradle.internal.build.IncludedBuildState#getAvailableModules()
                try {
                    Method method = build.getClass().getMethod("getAvailableModules");
                    Class<?> retType = method.getReturnType();
                    if (Set.class.isAssignableFrom(retType)) {
                        // We have identified the right method. Get the values out of it.
                        Set availableModules = (Set) method.invoke(build);
                        for (Object entry : availableModules) {
                            Field field = entry.getClass().getField("left");
                            Object value = field.get(entry);
                            if (value instanceof ModuleVersionIdentifier) {
                                ModuleVersionIdentifier mv = (ModuleVersionIdentifier) value;
                                identifiers.add(String.format(GROUP_ARTIFACT_VERSION_FORMAT, mv.getGroup(), mv.getName(), mv.getVersion()));
                            } else {
                                project.getLogger().debug("Unable to determine field type: {}", field);
                            }
                        }
                    } else {
                        project.getLogger().debug("Unable to determine method return type: {}", retType);
                    }
                } catch (ReflectiveOperationException e) {
                    project.getLogger().debug("Unable to determine the included projects.", e);
                }
            });
            return identifiers;
        });
    }

    /**
     * Get data (identified by the given key) that has been cached on the given Gradle project reference.
     *
     * @param project  the Gradle project reference.
     * @param key      the key used for caching the data.
     * @param supplier the function that needs to be executed in the event of a cache-miss.
     * @param <T>      the type of the data stored on the project.
     * @return data that has been cached on the given Gradle project reference.
     */
    @SuppressWarnings("unchecked")
    private static <T> T getCachedReference(Project project, String key, Supplier<T> supplier) {
        if (project == null) {
            throw new IllegalArgumentException("Gradle project reference cannot be null.");
        }

        Project rootProject = project.getRootProject();
        ExtraPropertiesExtension ext = rootProject.getExtensions().getExtraProperties();
        T value;
        if (ext.has(key)) {
            value = (T) ext.get(key);
        } else {
            value = supplier.get();
            ext.set(key, value);
        }
        return value;
    }

    /*
     * Debugging the logic within class is quite challenging since the execution happens within the Gradle daemon. The
     * below method will launch a JFrame and display the message that you provide. Uncomment when debugging this class.
     *
     * @param message the message to display in the UI.
     */
    /*
    private static void displayMessage(String message) {
        javax.swing.JFrame.setDefaultLookAndFeelDecorated(true);
        javax.swing.JFrame frame = new javax.swing.JFrame();
        frame.setTitle("Thorntail Debug Window");
        frame.setDefaultCloseOperation(javax.swing.JFrame.EXIT_ON_CLOSE);
        javax.swing.JTextArea textArea = new javax.swing.JTextArea();
        javax.swing.JScrollPane pane = new javax.swing.JScrollPane(textArea);
        textArea.setText(message);
        frame.add(pane);
        frame.pack();
        frame.setVisible(true);
    }
     */

    private static final String GROUP_ARTIFACT_FORMAT = "%s:%s";

    private static final String GROUP_ARTIFACT_VERSION_FORMAT = "%s:%s:%s";

    // Translate the different Gradle dependency scopes in to values that map to ShrinkWrap's scope type.
    // c.f.: https://docs.gradle.org/current/userguide/java_plugin.html#sec:java_plugin_and_dependency_management
    private static final Map<String, String> REMAPPED_SCOPES = new HashMap<>();

    static {
        // Avoid compiler warning.
        final String COMPILE = "compile";
        final String TEST = "test";
        final String PROVIDED = "provided";
        final String RUNTIME = "runtime";

        REMAPPED_SCOPES.put("platform-runtime", "import");
        REMAPPED_SCOPES.put("enforced-platform-runtime", "import");

        REMAPPED_SCOPES.put("compile", COMPILE);
        REMAPPED_SCOPES.put("api", COMPILE);
        REMAPPED_SCOPES.put("implementation", COMPILE);
        REMAPPED_SCOPES.put("apiElements", COMPILE);
        REMAPPED_SCOPES.put("compileClasspath", COMPILE);

        REMAPPED_SCOPES.put("providedCompile", PROVIDED);
        REMAPPED_SCOPES.put("compileOnly", PROVIDED);

        REMAPPED_SCOPES.put("runtime", RUNTIME);
        REMAPPED_SCOPES.put("runtimeOnly", RUNTIME);
        REMAPPED_SCOPES.put("runtimeElements", RUNTIME);
        REMAPPED_SCOPES.put("runtimeClasspath", RUNTIME);
        REMAPPED_SCOPES.put("providedRuntime", RUNTIME);

        REMAPPED_SCOPES.put("testImplementation", TEST);
        REMAPPED_SCOPES.put("testCompileOnly", TEST);
        REMAPPED_SCOPES.put("testRuntimeOnly", TEST);
        REMAPPED_SCOPES.put("testCompile", TEST);
        REMAPPED_SCOPES.put("testCompileClasspath", TEST);
        REMAPPED_SCOPES.put("testRuntime", TEST);
        REMAPPED_SCOPES.put("testRuntimeClasspath", TEST);

        // When using Spring's dependency-management-plugin, some of the artifact specs will end up with a scope of either
        // "master" or "default" which is not honored by ScopeType. This is observed when executing Arquillian based tests cases.
        //  We need to rewrite the scope to "compile".
        REMAPPED_SCOPES.put("default", COMPILE);
        REMAPPED_SCOPES.put("master", COMPILE);
    }
}
