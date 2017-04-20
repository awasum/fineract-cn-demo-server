/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.dev;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import io.mifos.accounting.api.v1.client.LedgerManager;
import io.mifos.anubis.api.v1.domain.AllowedOperation;
import io.mifos.core.api.config.EnableApiFactory;
import io.mifos.core.api.context.AutoSeshat;
import io.mifos.core.api.context.AutoUserContext;
import io.mifos.core.api.util.ApiConstants;
import io.mifos.core.api.util.ApiFactory;
import io.mifos.core.cassandra.util.CassandraConnectorConstants;
import io.mifos.core.lang.AutoTenantContext;
import io.mifos.core.mariadb.util.MariaDBConstants;
import io.mifos.core.test.env.TestEnvironment;
import io.mifos.core.test.listener.EventRecorder;
import io.mifos.core.test.servicestarter.ActiveMQForTest;
import io.mifos.core.test.servicestarter.EurekaForTest;
import io.mifos.core.test.servicestarter.IntegrationTestEnvironment;
import io.mifos.core.test.servicestarter.Microservice;
import io.mifos.customer.api.v1.client.CustomerManager;
import io.mifos.identity.api.v1.events.EventConstants;
import io.mifos.identity.api.v1.client.IdentityManager;
import io.mifos.identity.api.v1.domain.*;
import io.mifos.office.api.v1.client.OrganizationManager;
import io.mifos.portfolio.api.v1.client.PortfolioManager;
import io.mifos.provisioner.api.v1.client.Provisioner;
import io.mifos.provisioner.api.v1.domain.*;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.Base64Utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("SpringAutowiredFieldsWarningInspection")
@RunWith(SpringRunner.class)
@SpringBootTest()
public class ServiceRunner {
  private static final String CLIENT_ID = "service-runner";
  private static Microservice<Provisioner> provisionerService;
  private static Microservice<IdentityManager> identityService;
  private static Microservice<OrganizationManager> officeClient;
  private static Microservice<CustomerManager> customerClient;
  private static Microservice<LedgerManager> accountingClient;
  private static Microservice<PortfolioManager> portfolioClient;

  private static DB embeddedMariaDb;

  private static final String CUSTOM_PROP_PREFIX = "custom.";

  @Configuration
  @ActiveMQForTest.EnableActiveMQListen
  @EnableApiFactory
  @ComponentScan("io.mifos.dev.listener")
  public static class TestConfiguration {
    public TestConfiguration() {
      super();
    }

    @Bean()
    public Logger logger() {
      return LoggerFactory.getLogger("test-logger");
    }
  }

  @ClassRule
  public static final EurekaForTest EUREKA_FOR_TEST = new EurekaForTest();

  @ClassRule
  public static final ActiveMQForTest ACTIVE_MQ_FOR_TEST = new ActiveMQForTest();

  @ClassRule
  public static final IntegrationTestEnvironment INTEGRATION_TEST_ENVIRONMENT = new IntegrationTestEnvironment("fineract-demo");

  @Autowired
  private ApiFactory apiFactory;

  @Autowired
  private EventRecorder eventRecorder;

  @Autowired
  private Environment environment;

  public ServiceRunner() {
    super();
  }

  @Before
  public void before() throws Exception
  {
    if (!this.environment.containsProperty("demoserver.persistent")) {
      // start embedded Cassandra
      EmbeddedCassandraServerHelper.startEmbeddedCassandra(TimeUnit.SECONDS.toMillis(30L));
      // start embedded MariaDB
      ServiceRunner.embeddedMariaDb = DB.newEmbeddedDB(
          DBConfigurationBuilder.newBuilder()
              .setPort(3306)
              .build()
      );
      ServiceRunner.embeddedMariaDb.start();
    }

    ServiceRunner.provisionerService =
        new Microservice<>(Provisioner.class, "provisioner", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
    final TestEnvironment provisionerTestEnvironment = provisionerService.getProcessEnvironment();
    provisionerTestEnvironment.addSystemPrivateKeyToProperties();
    provisionerTestEnvironment.setProperty("system.initialclientid", ServiceRunner.CLIENT_ID);

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + CassandraConnectorConstants.CONTACT_POINTS_PROP)) {
      provisionerTestEnvironment.setProperty(CassandraConnectorConstants.CONTACT_POINTS_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + CassandraConnectorConstants.CONTACT_POINTS_PROP));
      System.out.println(provisionerTestEnvironment.getProperty(CassandraConnectorConstants.CONTACT_POINTS_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_HOST_PROP)) {
      provisionerTestEnvironment.setProperty(MariaDBConstants.MARIADB_HOST_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_HOST_PROP));
      System.out.println(provisionerTestEnvironment.getProperty(MariaDBConstants.MARIADB_HOST_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_USER_PROP)) {
      provisionerTestEnvironment.setProperty(MariaDBConstants.MARIADB_USER_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_USER_PROP));
      System.out.println(provisionerTestEnvironment.getProperty(MariaDBConstants.MARIADB_USER_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_PASSWORD_PROP)) {
      provisionerTestEnvironment.setProperty(MariaDBConstants.MARIADB_PASSWORD_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_PASSWORD_PROP));
      System.out.println(provisionerTestEnvironment.getProperty(MariaDBConstants.MARIADB_PASSWORD_PROP));
    }

    ServiceRunner.provisionerService.start();
    ServiceRunner.provisionerService.setApiFactory(apiFactory);

    final Properties generalProperties = new Properties();
    generalProperties.setProperty("server.max-http-header-size", Integer.toString(16 * 1024));
    generalProperties.setProperty("bonecp.partitionCount", "1");
    generalProperties.setProperty("bonecp.maxConnectionsPerPartition", "4");
    generalProperties.setProperty("bonecp.minConnectionsPerPartition", "1");
    generalProperties.setProperty("bonecp.acquireIncrement", "1");
    this.setAdditionalProperties(generalProperties);

    final Properties identityProperties = new Properties();
    identityProperties.putAll(generalProperties);
    identityProperties.setProperty("identity.token.refresh.secureCookie", "false");

    ServiceRunner.identityService = this.startService(IdentityManager.class, "identity", identityProperties);
    ServiceRunner.officeClient = this.startService(OrganizationManager.class, "office", generalProperties);
    ServiceRunner.customerClient = this.startService(CustomerManager.class, "customer", generalProperties);
    ServiceRunner.accountingClient = this.startService(LedgerManager.class, "accounting", generalProperties);
    ServiceRunner.portfolioClient = this.startService(PortfolioManager.class, "portfolio", generalProperties);
  }

  @After
  public void tearDown() throws Exception {
    ServiceRunner.portfolioClient.kill();
    ServiceRunner.accountingClient.kill();
    ServiceRunner.customerClient.kill();
    ServiceRunner.officeClient.kill();
    ServiceRunner.identityService.kill();
    ServiceRunner.provisionerService.kill();

    if (!this.environment.containsProperty("demoserver.persistent")) {
      ServiceRunner.embeddedMariaDb.stop();
      EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    }
  }

  @Test
  public void startDevServer() throws Exception {

    if (this.environment.containsProperty("demoserver.provision")) {
      this.provisionAppsViaSeshat();
    }

    System.out.println("Identity Service: " + ServiceRunner.identityService.getProcessEnvironment().serverURI());
    System.out.println("Office Service: " + ServiceRunner.officeClient.getProcessEnvironment().serverURI());
    System.out.println("Customer Service: " + ServiceRunner.customerClient.getProcessEnvironment().serverURI());
    System.out.println("Accounting Service: " + ServiceRunner.accountingClient.getProcessEnvironment().serverURI());
    System.out.println("Portfolio Service: " + ServiceRunner.portfolioClient.getProcessEnvironment().serverURI());

    boolean run = true;

    while (run) {
      final Scanner scanner = new Scanner(System.in);
      final String nextLine = scanner.nextLine();
      if (nextLine != null && nextLine.equals("exit")) {
        run = false;
      }
    }
  }

  private <T> Microservice<T> startService(final Class<T> serviceClass, final String serviceName, final Properties properties) throws Exception {
    final Microservice<T> microservice = new Microservice<>(serviceClass, serviceName, "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
    if (properties !=null) {
      properties.forEach((key, value) ->
              microservice.getProcessEnvironment().setProperty(key.toString(), value.toString()));
    }
    microservice.start();
    microservice.setApiFactory(this.apiFactory);
    return microservice;
  }

  private void provisionAppsViaSeshat() throws Exception {
    final AuthenticationResponse authenticationResponse =
        ServiceRunner.provisionerService.api().authenticate(ServiceRunner.CLIENT_ID, ApiConstants.SYSTEM_SU, "oS/0IiAME/2unkN1momDrhAdNKOhGykYFH/mJN20");

    final List<Application> applicationsToCreate = Arrays.asList(
        ApplicationBuilder.create(ServiceRunner.identityService.name(), ServiceRunner.identityService.uri()),
        ApplicationBuilder.create(ServiceRunner.officeClient.name(), ServiceRunner.officeClient.uri()),
        ApplicationBuilder.create(ServiceRunner.customerClient.name(), ServiceRunner.customerClient.uri()),
        ApplicationBuilder.create(ServiceRunner.accountingClient.name(), ServiceRunner.accountingClient.uri()),
        ApplicationBuilder.create(ServiceRunner.portfolioClient.name(), ServiceRunner.portfolioClient.uri())
    );

    final List<Tenant> tenantsToCreate = Arrays.asList(
        TenantBuilder.create("playground", "A place to mess around and have fun", "playground"),
        TenantBuilder.create("demo-cccu", "Demo for CCCU", "demo_cccu"),
        TenantBuilder.create("SKCUKNS1", "St Kitts Cooperative Credit Union", "SKCUKNS1"),
        TenantBuilder.create("PCCUKNS1", "Police Cooperative Credit Union", "PCCUKNS1"),
        TenantBuilder.create("FCCUKNS1", "FND Cooperative Credit Union", "FCCUKNS1"),
        TenantBuilder.create("NCCUKNN1", "Nevis Cooperative Credit Union", "NCCUKNN1")
    );

    try (final AutoSeshat ignored = new AutoSeshat(authenticationResponse.getToken())) {
      applicationsToCreate.forEach(application -> ServiceRunner.provisionerService.api().createApplication(application));
    }

    final AdminPasswordHolder adminPasswordHolder = new AdminPasswordHolder();
      tenantsToCreate.forEach(tenant -> {
        try (final AutoSeshat ignored = new AutoSeshat(authenticationResponse.getToken())) {
          ServiceRunner.provisionerService.api().createTenant(tenant);
          applicationsToCreate.forEach(application -> {
            if (application.getName().equals(ServiceRunner.identityService.name())) {
              final AssignedApplication assignedApplication = new AssignedApplication();
              assignedApplication.setName(ServiceRunner.identityService.name());

              final IdentityManagerInitialization identityManagerInitialization = ServiceRunner.provisionerService.api().assignIdentityManager(tenant.getIdentifier(), assignedApplication);
              adminPasswordHolder.setPassword(identityManagerInitialization.getAdminPassword());
            } else {
              final AssignedApplication assignedApplication = new AssignedApplication();
              assignedApplication.setName(application.getName());
              ServiceRunner.provisionerService.api().assignApplications(tenant.getIdentifier(), Collections.singletonList(assignedApplication));
              try {
                Thread.sleep(5000L);
              } catch (InterruptedException e) {
                //do nothing
              }
            }
          });
        }

        try (final AutoTenantContext autoTenantContext = new AutoTenantContext(tenant.getIdentifier())) {
          this.createAdmin(adminPasswordHolder.getPassword());
        } catch (final Exception ex) {
          ex.printStackTrace();
        }
    });
  }

  private void createAdmin(final String tenantAdminPassword) throws Exception {
    final String tenantAdminUser = "antony";
    final Authentication adminPasswordOnlyAuthentication = ServiceRunner.identityService.api().login(tenantAdminUser, tenantAdminPassword);
    try (final AutoUserContext ignored = new AutoUserContext(tenantAdminUser, adminPasswordOnlyAuthentication.getAccessToken()))
    {
      ServiceRunner.identityService.api().changeUserPassword(tenantAdminUser, new Password(tenantAdminPassword));
      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_USER_PASSWORD, tenantAdminUser));
    }
    final Authentication adminAuthentication = ServiceRunner.identityService.api().login(tenantAdminUser, tenantAdminPassword);

    try (final AutoUserContext ignored = new AutoUserContext(tenantAdminUser, adminAuthentication.getAccessToken())) {
      final Role fimsAdministratorRole = createOrgAdministratorRole();

      ServiceRunner.identityService.api().createRole(fimsAdministratorRole);
      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_ROLE, fimsAdministratorRole.getIdentifier()));

      final UserWithPassword fimsAdministratorUser = new UserWithPassword();
      fimsAdministratorUser.setIdentifier("operator");
      fimsAdministratorUser.setPassword(Base64Utils.encodeToString("init1@l".getBytes()));
      fimsAdministratorUser.setRole(fimsAdministratorRole.getIdentifier());

      ServiceRunner.identityService.api().createUser(fimsAdministratorUser);
      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_USER, fimsAdministratorUser.getIdentifier()));

      ServiceRunner.identityService.api().logout();
    }
  }

  private Role createOrgAdministratorRole() {
    final Permission employeeAllPermission = new Permission();
    employeeAllPermission.setAllowedOperations(AllowedOperation.ALL);
    employeeAllPermission.setPermittableEndpointGroupIdentifier(io.mifos.office.api.v1.PermittableGroupIds.EMPLOYEE_MANAGEMENT);

    final Permission officeAllPermission = new Permission();
    officeAllPermission.setAllowedOperations(AllowedOperation.ALL);
    officeAllPermission.setPermittableEndpointGroupIdentifier(io.mifos.office.api.v1.PermittableGroupIds.OFFICE_MANAGEMENT);

    final Permission userAllPermission = new Permission();
    userAllPermission.setAllowedOperations(AllowedOperation.ALL);
    userAllPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.IDENTITY_MANAGEMENT);

    final Permission roleAllPermission = new Permission();
    roleAllPermission.setAllowedOperations(AllowedOperation.ALL);
    roleAllPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.ROLE_MANAGEMENT);

    final Permission selfManagementPermission = new Permission();
    selfManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    selfManagementPermission.setPermittableEndpointGroupIdentifier(io.mifos.identity.api.v1.PermittableGroupIds.SELF_MANAGEMENT);

    final Role role = new Role();
    role.setIdentifier("orgadmin");
    role.setPermissions(
        Arrays.asList(
            employeeAllPermission,
            officeAllPermission,
            userAllPermission,
            roleAllPermission,
            selfManagementPermission
        )
    );

    return role;
  }

  private void setAdditionalProperties(final Properties properties) {
    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + CassandraConnectorConstants.CONTACT_POINTS_PROP)) {
      properties.setProperty(CassandraConnectorConstants.CONTACT_POINTS_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + CassandraConnectorConstants.CONTACT_POINTS_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_HOST_PROP)) {
      properties.setProperty(MariaDBConstants.MARIADB_HOST_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_HOST_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_USER_PROP)) {
      properties.setProperty(MariaDBConstants.MARIADB_USER_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_USER_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_PASSWORD_PROP)) {
      properties.setProperty(MariaDBConstants.MARIADB_PASSWORD_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + MariaDBConstants.MARIADB_PASSWORD_PROP));
    }
  }
}
