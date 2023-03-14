package io.vertx.gsoc2023.qotd;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
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
          .send(testContext.succeeding(response -> {
            testContext.verify(() -> {
              var size = response.body().size();
              var insertedQuote = response.body().getJsonObject(size - 1);
              assertEquals("Jen Barber", insertedQuote.getString("author"));
              testContext.completeNow();
            });
          }));
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
          .send(testContext.succeeding(response -> {
            testContext.verify(() -> {
              var size = response.body().size();
              var insertedQuote = response.body().getJsonObject(size - 1);
              assertEquals("Unknown", insertedQuote.getString("author"));
              testContext.completeNow();
            });
          }));
      });
  }

  @Test
  public void testNewQuoteMissingText(VertxTestContext testContext) {
    var payload = new JsonObject()
      .put("author", "Camus");
    webClient.post("/quotes")
      .timeout(5000)
      .expect(ResponsePredicate.SC_BAD_REQUEST)
      .sendJsonObject(payload, testContext.succeeding(response -> {
        testContext.verify(() -> {
          assertEquals(400, response.statusCode());
          testContext.completeNow();
        });
      }));
  }
}
