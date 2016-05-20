/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
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

package org.keycloak.testsuite.util;

import java.io.IOException;
import java.lang.reflect.Method;

import javax.ws.rs.core.Response;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;
import org.keycloak.common.util.ObjectUtil;
import org.keycloak.common.util.reflections.Reflections;
import org.keycloak.events.admin.OperationType;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.AdminEventRepresentation;
import org.keycloak.representations.idm.AuthDetailsRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.AbstractKeycloakTest;
import org.keycloak.testsuite.Assert;
import org.keycloak.util.JsonSerialization;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class AssertAdminEvents implements TestRule {

    private AbstractKeycloakTest context;

    public AssertAdminEvents(AbstractKeycloakTest ctx) {
        context = ctx;
    }

    @Override
    public Statement apply(final Statement base, org.junit.runner.Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                // TODO: Ideally clear the queue just before testClass rather then before each method
                context.getTestingClient().testing().clearAdminEventQueue();
                base.evaluate();
                // TODO Test should fail if there are leftover events
            }
        };
    }

    public AdminEventRepresentation poll() {
        AdminEventRepresentation event = fetchNextEvent();
        Assert.assertNotNull("Admin event expected", event);

        return event;
    }

    public void assertEmpty() {
        AdminEventRepresentation event = fetchNextEvent();
        Assert.assertNull("Empty admin event queue expected, but there is " + event, event);
    }

    // Clears both "classic" and admin events for now
    public void clear() {
        Response res = context.getTestingClient().testing().clearAdminEventQueue();
        try {
            Assert.assertEquals("clear-admin-event-queue success", res.getStatus(), 200);
        } finally {
            res.close();
        }
    }

    private AdminEventRepresentation fetchNextEvent() {
        return context.getTestingClient().testing().pollAdminEvent();
    }

    public ExpectedAdminEvent expect() {
        return new ExpectedAdminEvent();
    }



    public AdminEventRepresentation assertEvent(String realmId, OperationType operationType, String resourcePath) {
        return assertEvent(realmId, operationType, resourcePath, null);
    }

    public AdminEventRepresentation assertEvent(String realmId, OperationType operationType, Matcher<String> resourcePath) {
        return assertEvent(realmId, operationType, resourcePath, null);
    }

    public AdminEventRepresentation assertEvent(String realmId, OperationType operationType, String resourcePath, Object representation) {
        return assertEvent(realmId, operationType, Matchers.equalTo(resourcePath), representation);
    }

    public AdminEventRepresentation assertEvent(String realmId, OperationType operationType, Matcher<String> resourcePath, Object representation) {
        return expect().realmId(realmId)
                .operationType(operationType)
                .resourcePath(resourcePath)
                .representation(representation)
                .assertEvent();
    }



    public class ExpectedAdminEvent {

        private AdminEventRepresentation expected = new AdminEventRepresentation();
        private Matcher<String> resourcePath;
        private Object expectedRep;

        public ExpectedAdminEvent realmId(String realmId) {
            expected.setRealmId(realmId);
            return this;
        }

        public ExpectedAdminEvent realm(RealmRepresentation realm) {
            return realmId(realm.getId());
        }

        public ExpectedAdminEvent operationType(OperationType operationType) {
            expected.setOperationType(operationType.toString());
            updateOperationTypeIfError();
            return this;
        }

        public ExpectedAdminEvent resourcePath(String resourcePath) {
            return resourcePath(Matchers.equalTo(resourcePath));
        }

        public ExpectedAdminEvent resourcePath(Matcher<String> resourcePath) {
            this.resourcePath = resourcePath;
            return this;
        }

        public ExpectedAdminEvent error(String error) {
            expected.setError(error);
            updateOperationTypeIfError();
            return this;
        }

        private void updateOperationTypeIfError() {
            if (expected.getError() != null && expected.getOperationType() != null) {
                expected.setOperationType(expected.getOperationType() + "_ERROR");
            }
        }

        public ExpectedAdminEvent authDetails(String realmId, String clientId, String userId) {
            AuthDetailsRepresentation authDetails = new AuthDetailsRepresentation();
            authDetails.setRealmId(realmId);
            authDetails.setClientId(clientId);
            authDetails.setUserId(userId);
            expected.setAuthDetails(authDetails);
            return this;
        }

        public ExpectedAdminEvent representation(Object representation) {
            this.expectedRep = representation;
            return this;
        }

        public AdminEventRepresentation assertEvent() {
            return assertEvent(poll());
        }

        public AdminEventRepresentation assertEvent(AdminEventRepresentation actual) {
            Assert.assertEquals(expected.getRealmId(), actual.getRealmId());
            Assert.assertEquals(expected.getOperationType(), actual.getOperationType());
            Assert.assertThat(actual.getResourcePath(), resourcePath);

            Assert.assertTrue(ObjectUtil.isEqualOrBothNull(expected.getError(), actual.getError()));

            // AuthDetails
            AuthDetailsRepresentation expectedAuth = expected.getAuthDetails();
            if (expectedAuth == null) {
                expectedAuth = defaultAuthDetails();
            }

            AuthDetailsRepresentation actualAuth = actual.getAuthDetails();
            Assert.assertEquals(expectedAuth.getRealmId(), actualAuth.getRealmId());
            Assert.assertEquals(expectedAuth.getUserId(), actualAuth.getUserId());
            if (expectedAuth.getClientId() != null) {
                Assert.assertEquals(expectedAuth.getClientId(), actualAuth.getClientId());
            }

            // Representation - compare the non-null fields of "expected" representation with the actual representation
            if (expectedRep != null) {
                if (actual.getRepresentation() == null) {
                    Assert.fail("Expected representation " + expectedRep + " but no representation was available on actual event");
                } else {
                    try {
                        Object actualRep = JsonSerialization.readValue(actual.getRepresentation(), expectedRep.getClass());

                        for (Method method : Reflections.getAllDeclaredMethods(expectedRep.getClass())) {
                            if (method.getName().startsWith("get") || method.getName().startsWith("is")) {
                                Object expectedValue = Reflections.invokeMethod(method, expectedRep);
                                if (expectedValue != null) {
                                    Object actualValue = Reflections.invokeMethod(method, actualRep);
                                    Assert.assertEquals("Property " + method.getName() + " of representation not equal.", expectedValue, actualValue);
                                }
                            }
                        }
                    } catch (IOException ioe) {
                        throw new RuntimeException(ioe);
                    }
                }
            }

            return actual;
        }

    }

    private AuthDetailsRepresentation defaultAuthDetails() {
        String accessTokenString = context.getAdminClient().tokenManager().getAccessTokenString();
        try {
            JWSInput input = new JWSInput(accessTokenString);
            AccessToken token = input.readJsonContent(AccessToken.class);

            AuthDetailsRepresentation authDetails = new AuthDetailsRepresentation();
            String realmId = token.getIssuer().substring(token.getIssuer().lastIndexOf('/') + 1);
            authDetails.setRealmId(realmId);
            authDetails.setUserId(token.getSubject());
            return authDetails;
        } catch (JWSInputException jwe) {
            throw new RuntimeException(jwe);
        }
    }


}
