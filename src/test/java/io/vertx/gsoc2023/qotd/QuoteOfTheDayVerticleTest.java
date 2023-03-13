package io.vertx.gsoc2023.qotd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import java.util.List;
import java.util.stream.Collectors;

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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

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
  public void testPostQuoteWithAllInfo(VertxTestContext testContext) {
    JsonObject testQuote = new JsonObject()
      .put("author", "Francis Bacon")
      .put("text", "Knowledge is power");
  
    Checkpoint postQuoteCheck = testContext.checkpoint();
    Checkpoint getQuoteCheck = testContext.checkpoint();
    
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_CREATED)
      .sendJsonObject(testQuote, testContext.succeeding(postResponse -> {
        testContext.verify(() -> {
          assertEquals(201, postResponse.statusCode(), postResponse.bodyAsString());
          postQuoteCheck.flag();
        });
        
        webClient.get("/quotes")
          .as(BodyCodec.jsonArray())
          .expect(ResponsePredicate.SC_OK)
          .expect(ResponsePredicate.JSON)
          .send(testContext.succeeding(getResponse -> {
            testContext.verify(() -> {
              assertEquals(200, getResponse.statusCode(), postResponse.bodyAsString());
              JsonArray quotes = getResponse.body();
              
              List<JsonObject> quoteList = quotes.stream()
                .map(obj -> (JsonObject) obj)
                .filter(quote -> 
                  (testQuote.getString("author").equals(quote.getString("author"))
                      && testQuote.getString("text").equals(quote.getString("text"))))
                .collect(Collectors.toList());
              
              assertFalse(quoteList.isEmpty());
              getQuoteCheck.flag();
            });
        }));
      }));
  }
  
  @Test
  public void testPostQuoteWithoutAuthor(VertxTestContext testContext) {
    JsonObject testQuote = new JsonObject()
      .put("text", "Knowledge is power");
  
    Checkpoint postQuoteCheck = testContext.checkpoint();
    Checkpoint getQuoteCheck = testContext.checkpoint();
    
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_CREATED)
      .sendJsonObject(testQuote, testContext.succeeding(postResponse -> {
        testContext.verify(() -> {
          assertEquals(201, postResponse.statusCode(), postResponse.bodyAsString());
          postQuoteCheck.flag();
        });
        
        webClient.get("/quotes")
          .as(BodyCodec.jsonArray())
          .expect(ResponsePredicate.SC_OK)
          .expect(ResponsePredicate.JSON)
          .send(testContext.succeeding(getResponse -> {
            testContext.verify(() -> {
              assertEquals(200, getResponse.statusCode(), postResponse.bodyAsString());
              JsonArray quotes = getResponse.body();
              
              List<JsonObject> quoteList = quotes.stream()
                .map(obj -> (JsonObject) obj)
                .filter(quote -> 
                  ("Unknown".equals(quote.getString("author"))
                      && testQuote.getString("text").equals(quote.getString("text"))))
                .collect(Collectors.toList());
              
              assertFalse(quoteList.isEmpty());
              getQuoteCheck.flag();
            });
        }));
      }));
  }
  
  @Test
  public void testPostQuoteWithouText(VertxTestContext testContext) {
    JsonObject testQuote = new JsonObject()
      .put("author", "John Doe");
      
    webClient.post("/quotes")
      .expect(ResponsePredicate.SC_BAD_REQUEST)
      .sendJsonObject(testQuote, testContext.succeeding(postResponse -> {
        testContext.verify(() -> {
          assertEquals(400, postResponse.statusCode(), postResponse.bodyAsString());
          testContext.completeNow();
        });
      }));
  }
}
