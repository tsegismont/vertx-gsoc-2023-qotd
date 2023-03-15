package io.vertx.gsoc2023.qotd;

import io.vertx.core.Future;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;

import static io.vertx.core.Future.failedFuture;

public class QoTDHandlers {

  private final PgPool pool;
  private final EventBus eventBus;
  private final MessageConsumer<JsonObject> quoteInsertedConsumer;
  private static final String QUOTE_INSERTED = "quote_inserted";

  public QoTDHandlers(PgPool pool, EventBus eventBus) {
    this.pool = pool;
    this.eventBus = eventBus;
    this.quoteInsertedConsumer = eventBus.consumer(QUOTE_INSERTED);
  }

  public void getAllQuotes(RoutingContext ctx) {
    fetchAllQuotes()
      .onFailure(Throwable::printStackTrace)
      .onSuccess(quotes -> ctx
        .response()
        .setStatusCode(200)
        .putHeader("content-type", "application/json")
        .end(quotes.encode()));
  }

  public void postNewQuote(RoutingContext context) {
    var payload = context.body().asJsonObject();
    insertQuote(payload)
      .onFailure(cause -> context.response().setStatusCode(400).end())
      .onSuccess(quote -> {
        eventBus.send(QUOTE_INSERTED, quote);
        context.response().setStatusCode(201).end();
      });
  }

  public void realtimeQuotes(RoutingContext context) {
    context.request()
      .toWebSocket()
      .onSuccess(wsServer -> {
        quoteInsertedConsumer.handler(message -> {
          wsServer.writeBinaryMessage(message.body().toBuffer());
        });
      });
  }

  private Future<JsonArray> fetchAllQuotes() {
    return pool
      .query("SELECT * FROM quotes")
      .execute()
      .map(rs -> {
        var jsonArray = new JsonArray();
        rs.forEach(row -> jsonArray.add(row.toJson()));
        return jsonArray;
      });
  }

  private Future<JsonObject> insertQuote(JsonObject quote) {
    if (quote.getString("text") == null)
      return failedFuture(new IllegalArgumentException("The 'text' field must be provided"));
    return pool
      .getConnection()
      .compose(conn ->
        conn
          .preparedQuery("INSERT INTO quotes (text, author) VALUES ($1, $2) RETURNING quote_id")
          .execute(Tuple.of(quote.getString("text"), quote.getString("author", "Unknown")))
          .map(rows -> rows.iterator().next().getLong("quote_id"))
          .compose(quoteId -> conn
            .preparedQuery("SELECT * FROM quotes WHERE quote_id=$1")
            .execute(Tuple.of(quoteId))
            .map(rows -> rows.iterator().next().toJson()))
          .eventually(__ -> conn.close())
      );
  }
}
