/**
 * Copyright 2018 Red Hat, Inc, and individual contributors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.microprofile.jwtauth;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.json.JsonString;
import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.Claims;

/**
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 * <br>
 * Date: 8/8/18
 */
@Path("subject")
public class SubjectExposingResource {

    @Inject
    @Claim(standard = Claims.sub)
    private String subject;

    @Inject
    @Claim(standard = Claims.sub)
    private JsonString jsonSubject;

    @GET
    @RolesAllowed("MappedRole")
    @Path("secured")
    public String getSubjectSecured() {
        return subject;
    }

    @GET
    @RolesAllowed("MappedRole")
    @Path("secured/json-string")
    public String getSubjectSecuredJsonString() {
        return jsonSubject.toString().replaceAll("\"", "");
    }

    @GET
    @Path("unsecured")
    @PermitAll
    public String getSubjectUnsecured() {
        return subject;
    }

    @GET
    @Path("unsecured/json-string")
    @PermitAll
    public String getSubjectUnsecuredJsonString() {
        return jsonSubject == null ? null : jsonSubject.toString();
    }

}
