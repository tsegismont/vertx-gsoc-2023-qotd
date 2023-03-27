package io.vertx.gsoc2023.qotd;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocket;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.testcontainers.containers.BindMode.READ_ONLY;

@ExtendWith(VertxExtension.class)
@Testcontainers
public class QuoteOfTheDayVerticleTest {

  private static final int PORT = 8888;

  private Vertx vertx = Vertx.vertx();
  private WebClient webClient = WebClient.create(vertx, new WebClientOptions().setDefaultPort(PORT));
  private HttpClient httpClient = vertx.createHttpClient(new WebClientOptions().setDefaultPort(PORT));
  private Future<WebSocket> webSockClient;

  @Container
  public static GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:15.2"))
    .withEnv("POSTGRES_USER", "quotes")
    .withEnv("POSTGRES_PASSWORD", "super$ecret")
    .withEnv("POSTGRES_DB", "quotes")
    .withClasspathResourceMapping("import.sql", "/docker-entrypoint-initdb.d/import.sql", READ_ONLY)
    .withExposedPorts(5432);

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
      .send(testContext.succeeding(response -> {
        testContext.verify(() -> {
          assertEquals(200, response.statusCode(), response.bodyAsString());
          JsonArray quotes = response.body();
          assertFalse(quotes.isEmpty());
          testContext.completeNow();
        });
      }));
  }

  @Test
  public void testPostQuotesWithAuthorNameAndQuote(VertxTestContext testContext) {
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_OK)
      .sendJsonObject(
        new JsonObject()
          .put("author", "John Carmack")
          .put("text", "Focused hard work is the real key to success.")
      ).onComplete(testContext.succeeding(response -> {
        testContext.verify(() -> {
          assertEquals(200, response.statusCode(), response.bodyAsString());
          testContext.completeNow();
        });
      }));
  }

  @Test
  public void testPostQuotesWithOnlyQuote(VertxTestContext testContext) {
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_OK)
      .sendJsonObject(
        new JsonObject()
          .put("text", "Focused hard work is the real key to success.")
      ).onComplete(testContext.succeeding(response -> {
        testContext.verify(() -> {
          assertEquals(200, response.statusCode(), response.bodyAsString());
          testContext.completeNow();
        });
      }));
  }


  @Test
  public void testPostQuoteWithOnlyAuthor(VertxTestContext testContext) {
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_BAD_REQUEST) //400
      .sendJsonObject(
        new JsonObject()
          .put("author", "Eric Steve Raymond")
      ).onComplete(testContext.succeeding(response -> {
        testContext.verify(() -> {
          assertEquals(400, response.statusCode(), response.bodyAsString());
          testContext.completeNow();
        });
      }));
  }


  @Test
  public void testRealtimeRoute(VertxTestContext testContext) {
    httpClient.webSocket(
        new WebSocketConnectOptions()
          .setHost("localhost")
          .setURI("/realtime")
          .setTimeout(7000))
      .onSuccess(
         /*on successfully establishing a websocket connection,
          a websocket connection object is returned, we then make a post
          request that serializes JsonObject body of request  to it's
          event bus if the request is successful*/
        webSocksConn -> {
          webClient.post("/quotes")
            .sendJsonObject(
              new JsonObject()
                .put("author", "John Carmack")
                .put("text", "Low-level programming is good for the programmer's soul")
            );
          testContext.checkpoint().flag(); //The test will loom forver will this marker.
        }).onComplete(
         /*and of course we proceed to do the ususal validation when the Future returns*/
        testContext.succeeding(webSocksConn -> {
          webSocksConn.binaryMessageHandler(message -> {
            JsonObject msgContent = message.toJsonObject();
            assertEquals("John Carmack", msgContent.getString("author"));
            webSocksConn.close();
            testContext.completeNow();;
          });
        })
      );
  }
}
