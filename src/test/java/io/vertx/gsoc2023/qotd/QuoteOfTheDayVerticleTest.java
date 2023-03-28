package io.vertx.gsoc2023.qotd;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;
import static org.testcontainers.containers.BindMode.READ_ONLY;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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

  @Order(0)
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

  @Order(2)
  @Test
  public void testPostQuotes(VertxTestContext testContext) {
    String text = "The greatest glory in living lies not in never falling, but in rising every time we fall.";
    String author = "Nelson Mandela";
    var quote = new JsonObject().put("text", text).put("author", author);

    webClient
      .post("/quotes")
      .as(BodyCodec.jsonObject())
      .expect(ResponsePredicate.SC_OK)
      .expect(ResponsePredicate.JSON)
      .sendJsonObject(quote, testContext.succeeding(response -> testContext.verify(() -> {
        var body = response.body();
        assertNotNull(body.getInteger("quote_id"));
        assertEquals(text, body.getString("text"));
        assertEquals(author, body.getString("author"));

        testContext.completeNow();
      })));
  }

  @Order(3)
  @Test
  public void testPostQuotesUnknownAuthor(VertxTestContext testContext) {
    String text = "The way to get started is to quit talking and begin doing.";
    var quote = new JsonObject().put("text", text);

    webClient
      .post("/quotes")
      .as(BodyCodec.jsonObject())
      .expect(ResponsePredicate.SC_OK)
      .expect(ResponsePredicate.JSON)
      .sendJsonObject(quote, testContext.succeeding(response -> testContext.verify(() -> {
        var body = response.body();
        assertNotNull(body.getInteger("quote_id"));
        assertEquals(text, body.getString("text"));
        assertEquals("Unknown", body.getString("author"));

        testContext.completeNow();
      })));
  }

  @Order(4)
  @Test
  public void testPostQuotesUnknownText(VertxTestContext testContext) {
    String author = "Walt Disney";
    var quote = new JsonObject().put("author", author);

    webClient
      .post("/quotes")
      .expect(ResponsePredicate.SC_BAD_REQUEST)
      .sendJsonObject(quote, testContext.succeeding(response -> testContext.verify(testContext::completeNow)));
  }

  @Order(5)
  @Test
  public void testGetQuotesAfterPosts(VertxTestContext testContext) {
    webClient.get("/quotes")
      .as(BodyCodec.jsonArray())
      .expect(ResponsePredicate.SC_OK)
      .expect(ResponsePredicate.JSON)
      .send(testContext.succeeding(response -> testContext.verify(() -> {
        Object received = response.body();
        Object expected = Json.decodeValue("""
          [
            {
              "quote_id": 1,
              "text": "Hello, IT. Have you tried turning it off and on again?",
              "author":"Roy Trenneman"
            },
            {
              "quote_id": 2,
              "text": "Hello, IT... Have you tried forcing an unexpected reboot?",
              "author": "Maurice Moss"
            },
            {
              "quote_id": 3,
              "text": "The greatest glory in living lies not in never falling, but in rising every time we fall.",
              "author": "Nelson Mandela"
            },
            {
              "quote_id": 4,
              "text": "The way to get started is to quit talking and begin doing.",
              "author": "Unknown"
            }
          ]
          """);
        assertEquals(received, expected);
        testContext.completeNow();
      })));
  }

}
