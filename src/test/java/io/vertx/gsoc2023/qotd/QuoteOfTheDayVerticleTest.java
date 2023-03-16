package io.vertx.gsoc2023.qotd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

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
          JsonArray quotes = response.body();
          assertFalse(quotes.isEmpty());
          testContext.completeNow();
        });
      }));
  }
  
  @Test
  public void testPostQuoteWithAllInfo(VertxTestContext testContext) {
    JsonObject testQuote = new JsonObject()
      .put("author", "Francis Bacon")
      .put("text", "Knowledge is power");
      
    webClient.post("/quotes")
      .as(BodyCodec.jsonObject())
      .expect(ResponsePredicate.SC_CREATED)
      .expect(ResponsePredicate.JSON)
      .sendJsonObject(testQuote, testContext.succeeding(postResponse -> {
        testContext.verify(() -> {
          JsonObject createdQuote = postResponse.body();
          assertEquals(testQuote.getString("author"), createdQuote.getString("author"));
          assertEquals(testQuote.getString("text"), createdQuote.getString("text"));
          testContext.completeNow();
        });
      }));
  }
  
  @Test
  public void testPostQuoteWithoutAuthor(VertxTestContext testContext) {
    JsonObject testQuote = new JsonObject()
      .put("text", "Knowledge is power");
      
    webClient.post("/quotes")
      .as(BodyCodec.jsonObject())
      .expect(ResponsePredicate.SC_CREATED)
      .expect(ResponsePredicate.JSON)
      .sendJsonObject(testQuote, testContext.succeeding(postResponse -> {
        testContext.verify(() -> {
          JsonObject createdQuote = postResponse.body();
          assertEquals("Unknown", createdQuote.getString("author"));
          assertEquals(testQuote.getString("text"), createdQuote.getString("text"));
          testContext.completeNow();
        });
      }));
  }
  
  @Test
  public void testPostQuoteWithoutText(VertxTestContext testContext) {
    JsonObject testQuote = new JsonObject()
      .put("author", "John Doe");
      
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_BAD_REQUEST)
      .sendJsonObject(testQuote, testContext.succeeding(response -> testContext.completeNow()));

  }
  
  @Test
  public void testRealtimeWebSocket(VertxTestContext testContext) {
    JsonObject testQuote = new JsonObject()
      .put("author", "Oscar Wilde")
      .put("text", "Experience is the name everyone gives to their mistakes");
    
    httpClient.webSocket("/realtime")
      .onSuccess(webSocket -> {
        webSocket.textMessageHandler(message -> {
          testContext.verify(() -> {
            JsonObject receivedQuote = new JsonObject(message);
            assertEquals(testQuote.getString("author"), receivedQuote.getString("author"));
            assertEquals(testQuote.getString("text"), receivedQuote.getString("text"));
            testContext.completeNow();
          });
        });
      });
  
    webClient.post("/quotes")
      .sendJsonObject(testQuote);
  }

}
