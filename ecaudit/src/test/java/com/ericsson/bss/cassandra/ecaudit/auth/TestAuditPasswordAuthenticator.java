/*
 * Copyright 2018 Telefonaktiebolaget LM Ericsson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ericsson.bss.cassandra.ecaudit.auth;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.ericsson.bss.cassandra.ecaudit.AuditAdapter;
import com.ericsson.bss.cassandra.ecaudit.entry.Status;
import org.apache.cassandra.auth.DataResource;
import org.apache.cassandra.auth.IAuthenticator;
import org.apache.cassandra.auth.IAuthenticator.SaslNegotiator;
import org.apache.cassandra.auth.IResource;
import org.apache.cassandra.exceptions.AuthenticationException;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.StrictStubs.class)
public class TestAuditPasswordAuthenticator
{
    @Mock
    private IAuthenticator mockAuthenticator;

    @Mock
    private AuditAdapter mockAdapter;

    @Mock
    private SaslNegotiator mockNegotiator;

    private AuditPasswordAuthenticator authenticator;

    @Before
    public void before()
    {
        authenticator = new AuditPasswordAuthenticator(mockAuthenticator, mockAdapter);
    }

    @After
    public void after()
    {
        verifyNoMoreInteractions(mockAdapter);
    }

    @Test
    public void testRequireAuthDelegation()
    {
        when(mockAuthenticator.requireAuthentication()).thenReturn(true);
        assertThat(authenticator.requireAuthentication()).isEqualTo(true);
        verify(mockAuthenticator, times(1)).requireAuthentication();
    }

    @Test
    public void testProtectedResourcesDelegation()
    {
        Set<IResource> expectedResources = Collections.singleton(DataResource.fromName("data"));
        doReturn(expectedResources).when(mockAuthenticator).protectedResources();
        assertThat(authenticator.protectedResources()).isEqualTo(expectedResources);
        verify(mockAuthenticator, times(1)).protectedResources();
    }

    @Test
    public void testValidateConfigurationDelegation()
    {
        authenticator.validateConfiguration();
        verify(mockAuthenticator, times(1)).validateConfiguration();
    }

    @Test
    public void testSetupDelegation()
    {
        authenticator.setup();
        verify(mockAuthenticator, times(1)).setup();
        verify(mockAdapter, times(1)).setup();
    }

    @Test
    public void testLogOnRuntimeException() throws Exception
    {
        InetAddress clientAddress = InetAddress.getLocalHost();
        when(mockAuthenticator.newSaslNegotiator(any(InetAddress.class))).thenReturn(mockNegotiator);
        whenGetAuthUserThrowRuntimeException();

        SaslNegotiator negotiator = authenticator.newSaslNegotiator(clientAddress);

        byte[] clientResponse = createClientResponse("username", "secretpassword");
        negotiator.evaluateResponse(clientResponse);

        assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(negotiator::getAuthenticatedUser);

        verify(mockAdapter, times(1)).auditAuth(eq("username"), eq(clientAddress), eq(Status.ATTEMPT));
        verify(mockAdapter, times(1)).auditAuth(eq("username"), eq(clientAddress), eq(Status.FAILED));
    }

    @Test
    public void testLogOnFailure() throws Exception
    {
        InetAddress clientAddress = InetAddress.getLocalHost();
        when(mockAuthenticator.newSaslNegotiator(any(InetAddress.class))).thenReturn(mockNegotiator);
        whenGetAuthUserThrowAuthException();

        SaslNegotiator negotiator = authenticator.newSaslNegotiator(clientAddress);

        byte[] clientResponse = createClientResponse("username", "secretpassword");
        negotiator.evaluateResponse(clientResponse);

        assertThatExceptionOfType(AuthenticationException.class)
        .isThrownBy(negotiator::getAuthenticatedUser);

        verify(mockAdapter, times(1)).auditAuth(eq("username"), eq(clientAddress), eq(Status.ATTEMPT));
        verify(mockAdapter, times(1)).auditAuth(eq("username"), eq(clientAddress), eq(Status.FAILED));
    }

    @Test
    public void testLogOnSuccess() throws Exception
    {
        InetAddress clientAddress = InetAddress.getLocalHost();
        when(mockAuthenticator.newSaslNegotiator(any(InetAddress.class))).thenReturn(mockNegotiator);

        SaslNegotiator negotiator = authenticator.newSaslNegotiator(clientAddress);

        byte[] clientResponse = createClientResponse("user", "password");
        negotiator.evaluateResponse(clientResponse);

        negotiator.getAuthenticatedUser();
        verify(mockAdapter, times(1)).auditAuth(eq("user"), eq(clientAddress), eq(Status.ATTEMPT));
    }

    @SuppressWarnings("unchecked")
    private void whenGetAuthUserThrowRuntimeException()
    {
        when(mockNegotiator.getAuthenticatedUser()).thenThrow(RuntimeException.class);
    }

    @SuppressWarnings("unchecked")
    private void whenGetAuthUserThrowAuthException()
    {
        when(mockNegotiator.getAuthenticatedUser()).thenThrow(AuthenticationException.class);
    }

    /**
     * Create a client response as SASL encoded bytes
     *
     * @param username the user name
     * @param password the user password
     * @return a plain-sasl encoded byte buffer
     */
    private static byte[] createClientResponse(String username, String password)
    {
        // Encoded format is: <NUL>username<NUL>password
        final int capacity = 1 + username.length() + 1 + password.length();
        byte[] data = new byte[capacity];

        ByteBuffer bb = ByteBuffer.allocate(capacity);
        bb.put((byte) 0x00); // <NUL>
        bb.put(username.getBytes(StandardCharsets.UTF_8)); // username
        bb.put((byte) 0x00); // <NUL>
        bb.put(password.getBytes(StandardCharsets.UTF_8)); // password

        // Get the data as a byte array
        bb.rewind();
        bb.get(data);
        return data;
    }
}