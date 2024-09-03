/**
 * Copyright 2015-2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.container.config;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.wildfly.swarm.spi.api.config.ConfigKey;

import static org.fest.assertions.Assertions.assertThat;

/**
 * @author Bob McWhirter
 */
public class EnvironmentConfigNodeFactoryTest {

    @Test
    public void testLoadSimple() {

        Map<String, String> env = new HashMap<String, String>() {{
            put("thorntail.name", "bob");
            put("thorntail.cheese", "cheddar");
            put("not.thorntail.taco", "crunchy");
            put("thorntail.foobar", "<<null>>");
        }};

        ConfigNode node = EnvironmentConfigNodeFactory.load(env);

        assertThat(node.valueOf(ConfigKey.parse("thorntail.name"))).isEqualTo("bob");
        assertThat(node.valueOf(ConfigKey.parse("thorntail.cheese"))).isEqualTo("cheddar");
        assertThat(node.valueOf(ConfigKey.parse("not.thorntail.taco"))).isNull();
        assertThat(node.valueOf(ConfigKey.parse("thorntail.foobar"))).isNull();
    }

    @Test
    public void testLoadSimpleBackwardsCompatible() {

        Map<String, String> env = new HashMap<String, String>() {{
            put("swarm.name", "bob");
            put("swarm.cheese", "cheddar");
            put("not.swarm.taco", "crunchy");
            put("swarm.foobar", "<<null>>");
        }};

        ConfigNode node = EnvironmentConfigNodeFactory.load(env);

        assertThat(node.valueOf(ConfigKey.parse("thorntail.name"))).isEqualTo("bob");
        assertThat(node.valueOf(ConfigKey.parse("thorntail.cheese"))).isEqualTo("cheddar");
        assertThat(node.valueOf(ConfigKey.parse("not.thorntail.taco"))).isNull();
        assertThat(node.valueOf(ConfigKey.parse("thorntail.foobar"))).isNull();
    }

    @Test
    public void testLoadNested() {
        Map<String, String> env = new HashMap<String, String>() {{
            put("thorntail.http.port", "8080");
            put("thorntail.data-sources.ExampleDS.url", "jdbc:db");
            put("THORNTAIL_DATA_DASH_SOURCES_EXAMPLEDS_JNDI_DASH_NAME", "java:/jboss/datasources/example");
            put("THORNTAIL_DATA_UNDERSCORE_SOURCES_EXAMPLEDS_USER_DASH_NAME", "joe");
            put("thorntail.foo.bar", "<<null>>");
            put("THORNTAIL_BAZ_QUUX", "<<null>>");
        }};

        ConfigNode node = EnvironmentConfigNodeFactory.load(env);

        assertThat(node.valueOf(ConfigKey.of("thorntail", "http", "port"))).isEqualTo("8080");
        assertThat(node.valueOf(ConfigKey.of("thorntail", "data-sources", "ExampleDS", "url"))).isEqualTo("jdbc:db");
        assertThat(node.valueOf(ConfigKey.of("thorntail", "data-sources", "ExampleDS", "jndi-name"))).isEqualTo("java:/jboss/datasources/example");
        assertThat(node.valueOf(ConfigKey.of("thorntail", "data_sources", "ExampleDS", "user-name"))).isEqualTo("joe");
        assertThat(node.valueOf(ConfigKey.of("thorntail", "foo", "bar"))).isNull();
        assertThat(node.valueOf(ConfigKey.of("thorntail", "baz", "quux"))).isNull();
    }

    @Test
    public void testLoadNestedBackwardsCompatible() {
        Map<String, String> env = new HashMap<String, String>() {{
            put("swarm.http.port", "8080");
            put("swarm.data-sources.ExampleDS.url", "jdbc:db");
            put("SWARM_DATA_DASH_SOURCES_EXAMPLEDS_JNDI_DASH_NAME", "java:/jboss/datasources/example");
            put("SWARM_DATA_UNDERSCORE_SOURCES_EXAMPLEDS_USER_DASH_NAME", "joe");
            put("swarm.foo.bar", "<<null>>");
            put("SWARM_BAZ_QUUX", "<<null>>");
        }};

        ConfigNode node = EnvironmentConfigNodeFactory.load(env);

        assertThat(node.valueOf(ConfigKey.of("thorntail", "http", "port"))).isEqualTo("8080");
        assertThat(node.valueOf(ConfigKey.of("thorntail", "data-sources", "ExampleDS", "url"))).isEqualTo("jdbc:db");
        assertThat(node.valueOf(ConfigKey.of("thorntail", "data-sources", "ExampleDS", "jndi-name"))).isEqualTo("java:/jboss/datasources/example");
        assertThat(node.valueOf(ConfigKey.of("thorntail", "data_sources", "ExampleDS", "user-name"))).isEqualTo("joe");
        assertThat(node.valueOf(ConfigKey.of("thorntail", "foo", "bar"))).isNull();
        assertThat(node.valueOf(ConfigKey.of("thorntail", "baz", "quux"))).isNull();
    }
}
