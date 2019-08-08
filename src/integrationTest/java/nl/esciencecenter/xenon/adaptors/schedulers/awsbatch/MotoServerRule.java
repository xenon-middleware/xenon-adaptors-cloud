/*
 * Copyright 2018 Netherlands eScience Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.esciencecenter.xenon.adaptors.schedulers.awsbatch;

import java.io.IOException;
import java.net.ServerSocket;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.batch.AWSBatch;
import com.amazonaws.services.batch.AWSBatchClientBuilder;
import com.amazonaws.services.batch.model.CEState;
import com.amazonaws.services.batch.model.CEType;
import com.amazonaws.services.batch.model.ComputeEnvironmentOrder;
import com.amazonaws.services.batch.model.ContainerProperties;
import com.amazonaws.services.batch.model.CreateComputeEnvironmentRequest;
import com.amazonaws.services.batch.model.CreateJobQueueRequest;
import com.amazonaws.services.batch.model.JQState;
import com.amazonaws.services.batch.model.JobDefinitionType;
import com.amazonaws.services.batch.model.RegisterJobDefinitionRequest;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.CreateRoleRequest;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MotoServerRule extends ExternalResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(MotoServerRule.class);
    private Process proc;
    private int port;

    @Override
    protected void before() throws Throwable {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String cmd = System.getenv("MOTO_SERVER");
        if (cmd == null) {
            cmd = "moto_server";
        }
        cmd += " --port " + port;
        LOGGER.debug("Starting: " + cmd);
        try {
            proc = Runtime.getRuntime().exec(cmd);
        } catch (IOException e) {
            throw new MotoServerException("Failed to execute `" + cmd + "`, required for AWS Batch integration tests, install with `pip3 install moto[server]` or define location with MOTO_SERVER env var", e);
        }
        // TODO do not swallow output, pipe stdout/stder of moto_server to logger
        proc.getErrorStream().read();

        AWSStaticCredentialsProvider cred = new AWSStaticCredentialsProvider(new BasicAWSCredentials("someaccesskey", "somesecretkey"));
        AwsClientBuilder.EndpointConfiguration endpoint = new AwsClientBuilder.EndpointConfiguration(getEndpoint(), "us-east-1");

        AmazonIdentityManagement iamClient = AmazonIdentityManagementClientBuilder.standard().withCredentials(cred).withEndpointConfiguration(endpoint).build();
        String serviceRole = iamClient.createRole(new CreateRoleRequest().withRoleName("TestRole").withAssumeRolePolicyDocument("/AWSBatchFullAccess")).getRole().getArn();
        iamClient.shutdown();

        AWSBatch batchClient = AWSBatchClientBuilder.standard().withCredentials(cred).withEndpointConfiguration(endpoint).build();

        String ceArn = batchClient.createComputeEnvironment(new CreateComputeEnvironmentRequest()
            .withComputeEnvironmentName("computedefinition1")
            .withType(CEType.UNMANAGED)
            .withState(CEState.ENABLED)
            .withServiceRole(serviceRole)
        ).getComputeEnvironmentArn();

        batchClient.createJobQueue(new CreateJobQueueRequest()
            .withJobQueueName("jobqueue1")
            .withState(JQState.ENABLED)
            .withPriority(123)
            .withComputeEnvironmentOrder(new ComputeEnvironmentOrder().withComputeEnvironment(ceArn).withOrder(123))
        ).getJobQueueArn();

        batchClient.registerJobDefinition(new RegisterJobDefinitionRequest()
            .withJobDefinitionName("jobdef1")
            .withType(JobDefinitionType.Container)
            .withContainerProperties(new ContainerProperties()
                .withImage("busybox")
                .withCommand("sleep", "10")
                .withMemory(128)
                .withVcpus(1)
            )
        );

        batchClient.shutdown();
    }

    @Override
    protected void after() {
        super.after();
        proc.destroyForcibly();
    }

    String getEndpoint() {
        return "http://localhost:" + port;
    }

    String[] getQueues() {
        return new String[] { "jobdef1:1!jobqueue1" };
    }

    String getDefaultQueue() {
        return getQueues()[0];
    }

    private static class MotoServerException extends Throwable {
        MotoServerException(String s, IOException e) {
            super(s, e);
        }
    }
}
