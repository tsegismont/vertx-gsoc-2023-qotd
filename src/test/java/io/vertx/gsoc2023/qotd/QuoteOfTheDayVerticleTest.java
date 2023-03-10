package io.vertx.gsoc2023.qotd;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocketBase;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.testcontainers.containers.BindMode.READ_ONLY;

@ExtendWith(VertxExtension.class)
@Testcontainers
public class QuoteOfTheDayVerticleTest {

  private static final int PORT = 8888;

  private final Vertx vertx = Vertx.vertx();
  private final WebClient webClient = WebClient.create(vertx, new WebClientOptions().setDefaultPort(PORT));

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
  void testGetQuotes(VertxTestContext testContext) {
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
  void testAddQuoteWithNoText(VertxTestContext testContext) {
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_BAD_REQUEST)
      .sendJsonObject(new JsonObject().put("author", "Shakespeare"),
        testContext.succeeding(response -> {
          testContext.verify(() -> {
            String body = response.bodyAsString();
            assertEquals(400, response.statusCode(), body);
            testContext.completeNow();
          });
        }));
  }

  @Test
  void testAddQuoteWithNoAuthor(VertxTestContext testContext) {
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_OK)
      .expect(ResponsePredicate.JSON)
      .sendJsonObject(new JsonObject().put("text", "To be, or not to be"),
        testContext.succeeding(response -> {
          testContext.verify(() -> {
            assertEquals(200, response.statusCode(), response.bodyAsString());
            JsonObject body = response.bodyAsJsonObject();
            assertEquals("To be, or not to be", body.getString("text"));
            assertEquals("Unknown", body.getString("author"));
            assertNotNull(body.getInteger("quote_id"));
            testContext.completeNow();
          });
        }));
  }

  @Test
  void testAddQuote(VertxTestContext testContext) {
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_OK)
      .expect(ResponsePredicate.JSON)
      .sendJsonObject(new JsonObject().put("text", "To be, or not to be").put("author", "Shakespeare"),
        testContext.succeeding(response -> {
          testContext.verify(() -> {
            assertEquals(200, response.statusCode(), response.bodyAsString());
            JsonObject body = response.bodyAsJsonObject();
            assertEquals("To be, or not to be", body.getString("text"));
            assertEquals("Shakespeare", body.getString("author"));
            assertNotNull(body.getInteger("quote_id"));
            testContext.completeNow();
          });
        }));
  }

  @Test
  void testAddQuoteWithNullRequest(VertxTestContext testContext) {
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_BAD_REQUEST)
      .sendJsonObject(null,
        testContext.succeeding(response -> {
          testContext.verify(() -> {
            String body = response.bodyAsString();
            assertEquals(400, response.statusCode(), body);
            testContext.completeNow();
          });
        }));
  }

  @Test
  void testAddQuoteWithStringRequest(VertxTestContext testContext) {
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_BAD_REQUEST)
      .sendBuffer(Buffer.buffer("test"),
        testContext.succeeding(response -> {
          testContext.verify(() -> {
            String body = response.bodyAsString();
            assertEquals(400, response.statusCode(), body);
            testContext.completeNow();
          });
        }));
  }

  @Test
  void testRealtime(VertxTestContext testContext) {
    vertx.createHttpClient().webSocket(PORT, "localhost", "/realtime",
      testContext.succeeding(ws ->
        ws.binaryMessageHandler(buffer -> {
          JsonObject jsonObject = buffer.toJsonObject();
          testContext.verify(() -> {
            assertEquals("To be, or not to be", jsonObject.getString("text"));
            assertEquals("Unknown", jsonObject.getString("author"));
            testContext.completeNow();
          });
        })));
    webClient.post("/quotes").sendJsonObject(new JsonObject().put("text", "To be, or not to be"));
  }

}
