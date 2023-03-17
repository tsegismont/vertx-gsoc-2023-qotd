package io.vertx.gsoc2023.qotd;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;
import static org.testcontainers.containers.BindMode.READ_ONLY;

@ExtendWith(VertxExtension.class)
@Testcontainers
public class QuoteOfTheDayVerticleTest {

  private static final int PORT = 8888;

  private Vertx vertx = Vertx.vertx();
  private WebClient webClient = WebClient.create(vertx, new WebClientOptions().setDefaultPort(PORT));
  private HttpClient httpClient = vertx.createHttpClient(new HttpClientOptions().setDefaultPort(PORT));

  @Container
  public static GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:15.2"))
    .withEnv("POSTGRES_USER", "quotes")
    .withEnv("POSTGRES_PASSWORD", "super$ecret")
    .withEnv("POSTGRES_DB", "quotes")
    .withClasspathResourceMapping("import.sql", "/docker-entrypoint-initdb.d/import.sql", READ_ONLY)
    .withExposedPorts(5432);

  @Nested
  class WhenConnectingToContainer {

    @BeforeEach
    public void setup(VertxTestContext testContext) {
      JsonObject config = new JsonObject()
        .put("dbUser", "quotes")
        .put("dbPassword", "super$ecret")
        .put("dbName", "quotes")
        .put("dbPort", postgres.getMappedPort(5432))
        .put("httpPort", PORT);
      DeploymentOptions deploymentOptions = new DeploymentOptions().setConfig(config);
      vertx.deployVerticle(new QuoteOfTheDayVerticle(), deploymentOptions, testContext.succeedingThenComplete());
    }

    @AfterEach
    public void tearDown(VertxTestContext testContext) {
      vertx.close(testContext.succeedingThenComplete());
    }

    @Test
    public void testGetQuotes(VertxTestContext testContext) {
      webClient.get("/quotes")
        .as(BodyCodec.jsonArray())
        .expect(ResponsePredicate.SC_OK)
        .expect(ResponsePredicate.JSON)
        .send(testContext.succeeding(response ->
          testContext.verify(() -> {
            assertEquals(200, response.statusCode(), response.bodyAsString());
            JsonArray quotes = response.body();
            assertFalse(quotes.isEmpty());
            testContext.completeNow();
          })));
    }

    @Test
    public void testNewQuoteWithAuthor(VertxTestContext testContext) {
      var payload = new JsonObject()
        .put("text", "Have you tried double-clicking the icon?")
        .put("author", "Jen Barber");
      var postRequest = webClient.post("/quotes")
        .timeout(5000)
        .expect(ResponsePredicate.SC_CREATED)
        .sendJsonObject(payload);
      testContext.assertComplete(postRequest)
        .onComplete(__ -> {
          webClient.get("/quotes")
            .as(BodyCodec.jsonArray())
            .send(testContext.succeeding(response ->
              testContext.verify(() -> {
                var size = response.body().size();
                var insertedQuote = response.body().getJsonObject(size - 1);
                assertEquals("Jen Barber", insertedQuote.getString("author"));
                testContext.completeNow();
              })));
        });
    }

    @Test
    public void testNewQuoteWithoutAuthor(VertxTestContext testContext) {
      var payload = new JsonObject()
        .put("text", "Life is too short.");
      var postRequest = webClient.post("/quotes")
        .timeout(5000)
        .expect(ResponsePredicate.SC_CREATED)
        .sendJsonObject(payload);
      testContext.assertComplete(postRequest)
        .onComplete(__ -> {
          webClient.get("/quotes")
            .as(BodyCodec.jsonArray())
            .send(testContext.succeeding(response ->
              testContext.verify(() -> {
                var size = response.body().size();
                var insertedQuote = response.body().getJsonObject(size - 1);
                assertEquals("Unknown", insertedQuote.getString("author"));
                testContext.completeNow();
              })));
        });
    }

    @Test
    public void testNewQuoteMissingText(VertxTestContext testContext) {
      var payload = new JsonObject()
        .put("author", "Camus");
      webClient.post("/quotes")
        .timeout(5000)
        .expect(ResponsePredicate.SC_BAD_REQUEST)
        .sendJsonObject(payload, testContext.succeeding(response ->
          testContext.verify(() -> {
            assertEquals(400, response.statusCode());
            testContext.completeNow();
          })));
    }

    @Nested
    class RealtimeTest {

      private WebSocketConnectOptions wsOptions;

      @BeforeEach
      void setup() {
        wsOptions = new WebSocketConnectOptions()
          .setTimeout(5000)
          .setHost("localhost")
          .setURI("/realtime");
      }

      @Test
      public void testRealtimeQuotes(VertxTestContext testContext) {
        var postRequestSent = testContext.checkpoint();
        var connectedToWs = testContext.checkpoint();
        var receivedData = testContext.checkpoint();
        httpClient
          .webSocket(wsOptions)
          .onSuccess(ws -> {
            var postQuote = new JsonObject()
              .put("text", "Vert.x is the state of the art in the reactive tooling for Java")
              .put("author", "Denis Julio");
            webClient.post("/quotes")
              .timeout(5000)
              .sendJsonObject(postQuote)
              .onSuccess(__ -> postRequestSent.flag());
            connectedToWs.flag();
          })
          .onComplete(testContext.succeeding(ws ->
            ws.binaryMessageHandler(buffer ->
              testContext.verify(() -> {
                var newQuote = buffer.toJsonObject();
                assertNotNull(newQuote.getString("quote_id"));
                assertEquals("Denis Julio", newQuote.getString("author"));
                ws.close();
                receivedData.flag();
              }))));
      }

      @Test
      public void testRealtimeQuotesWithouAuthor(VertxTestContext testContext) {
        var postRequestSent = testContext.checkpoint();
        var connectedToWs = testContext.checkpoint();
        var receivedData = testContext.checkpoint();
        httpClient
          .webSocket(wsOptions)
          .onSuccess(ws -> {
            var postQuote = new JsonObject()
              .put("text", "You shall not PASS!!!");
            webClient.post("/quotes")
              .timeout(5000)
              .sendJsonObject(postQuote)
              .onSuccess(__ -> postRequestSent.flag());
            connectedToWs.flag();
          })
          .onComplete(testContext.succeeding(ws ->
            ws.binaryMessageHandler(buffer ->
              testContext.verify(() -> {
                var newQuote = buffer.toJsonObject();
                assertNotNull(newQuote.getString("quote_id"));
                assertEquals("Unknown", newQuote.getString("author"));
                ws.close();
                receivedData.flag();
              }))));
      }
    }
  }

  @Nested
  class WhenFailToConnectToContainer {

    @BeforeEach
    public void setup(VertxTestContext testContext) {
      JsonObject config = new JsonObject()
        .put("dbUser", "quotes")
        .put("dbPassword", "super")
        .put("dbName", "quotes")
        .put("dbPort", postgres.getMappedPort(5432))
        .put("httpPort", PORT);
      DeploymentOptions deploymentOptions = new DeploymentOptions().setConfig(config);
      vertx.deployVerticle(new QuoteOfTheDayVerticle(), deploymentOptions, testContext.succeedingThenComplete());
    }

    @Test
    public void testGetQuotesHandleError(VertxTestContext testContext) {
      webClient.get("/quotes")
        .as(BodyCodec.jsonObject())
        .expect(ResponsePredicate.SC_INTERNAL_SERVER_ERROR)
        .send(testContext.succeeding(response -> {
            testContext.verify(() -> {
              assertNotNull(response.body().getString("message"));
              testContext.completeNow();
            });
          })
        );
    }

    @AfterEach
    public void tearDown(VertxTestContext testContext) {
      vertx.close(testContext.succeedingThenComplete());
    }
  }
}
