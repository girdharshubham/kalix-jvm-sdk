/* This code was generated by Kalix tooling.
 * As long as this file exists it will not be re-generated.
 * You are free to make changes to this file.
 */
package customer.view;

import akka.stream.javadsl.Sink;
import kalix.javasdk.testkit.junit.KalixTestKitResource;
import customer.Main;
import customer.api.CustomerApi;
import customer.api.CustomerService;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;

// Example of an integration test calling our service via the Kalix proxy
// Run all test classes ending with "IntegrationTest" using `mvn verify -Pit`
public class CustomerByNameIntegrationTest {

  /**
   * The test kit starts both the service container and the Kalix proxy.
   */
  @ClassRule
  public static final KalixTestKitResource testKit =
          new KalixTestKitResource(Main.createKalix());

  /**
   * Use the generated gRPC client to call the service through the Kalix proxy.
   */
  private final CustomerService apiClient;
  private final CustomerByName viewClient;

  public CustomerByNameIntegrationTest() {
    apiClient = testKit.getGrpcClient(CustomerService.class);
    viewClient = testKit.getGrpcClient(CustomerByName.class);
  }

  @Test
  public void findByEmail() throws Exception {
    String id1 = UUID.randomUUID().toString();
    apiClient.create(CustomerApi.Customer.newBuilder()
            .setCustomerId(id1)
            .setName("Johanna")
            .setEmail("foo@example.com")
            .build())
        .toCompletableFuture()
        .get(5, SECONDS);

    String id2 = UUID.randomUUID().toString();
    apiClient.create(CustomerApi.Customer.newBuilder()
            .setCustomerId(id2)
            .setName("Johanna")
            .setEmail("foo@example.com")
            .build())
        .toCompletableFuture()
        .get(5, SECONDS);

    CustomerViewModel.ByNameRequest req =
        CustomerViewModel.ByNameRequest.newBuilder().setCustomerName("Johanna").build();

    // the view is eventually updated
    await().atMost(20, SECONDS).until(() -> viewClient.getCustomers(req).runWith(Sink.seq(), testKit.getActorSystem()).toCompletableFuture()
        .get(3, SECONDS).size() == 2);

    List<CustomerViewModel.CustomerViewState> result = viewClient.getCustomers(req).runWith(Sink.seq(), testKit.getActorSystem()).toCompletableFuture()
        .get(5, SECONDS);
    assertEquals(2, result.size());

    assertEquals(
        new HashSet<>(Arrays.asList(id1, id2)),
        new HashSet<>(Arrays.asList(result.get(0).getCustomerId(), result.get(1).getCustomerId())));
  }

}
