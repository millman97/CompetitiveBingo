package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.model.ApiError;
import com.millman97.competitivebingo.model.BoardView;
import com.millman97.competitivebingo.model.EventBatchResponse;
import com.millman97.competitivebingo.model.EventEnvelope;
import com.millman97.competitivebingo.model.GameConfig;
import com.millman97.competitivebingo.model.Protocol;
import com.millman97.competitivebingo.model.ReadinessReport;
import com.millman97.competitivebingo.model.SyncRequest;
import com.millman97.competitivebingo.model.SyncResponse;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The five endpoints in docs/api-contract.md.
 *
 * Every method blocks, and is called from the plugin's single-threaded executor rather than
 * the client thread, so events keep their observed order with one request in flight. Backoff
 * is scheduled, never slept.
 */
@Slf4j
@Singleton
public class BingoApi
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Set<Call> calls = new HashSet<>();
	private boolean enabled;
	private long requestGeneration;
	/** Re-enabling the API must not authorize work queued for an earlier connection. */
	private final ThreadLocal<Long> requestScope = new ThreadLocal<>();

	@Inject
	public BingoApi(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient == null ? null : okHttpClient.newBuilder()
			.followRedirects(false)
			.followSslRedirects(false)
			.callTimeout(30, TimeUnit.SECONDS)
			.build();
		// Nulls are written explicitly so the wire shape matches the published schema.
		this.gson = Protocol.wireGson(gson);
	}

	public synchronized void setEnabled(boolean enabled)
	{
		cancelRequests();
		this.enabled = enabled;
	}

	public synchronized void cancelRequests()
	{
		requestGeneration++;
		for (Call call : calls)
		{
			call.cancel();
		}
		calls.clear();
	}

	synchronized long getRequestGeneration()
	{
		return requestGeneration;
	}

	void withGeneration(long expected, Runnable task)
	{
		final Long previous = requestScope.get();
		requestScope.set(expected);
		try
		{
			task.run();
		}
		finally
		{
			if (previous == null) { requestScope.remove(); }
			else { requestScope.set(previous); }
		}
	}

	int eventSize(EventEnvelope envelope)
	{
		return gson.toJson(envelope).getBytes(StandardCharsets.UTF_8).length;
	}

	/**
	 * Validates and normalises what the player typed into the server URL box.
	 *
	 * @throws ApiException if it is not a URL this plugin can use
	 */
	public static HttpUrl parseBaseUrl(String serverUrl) throws ApiException
	{
		final String trimmed = serverUrl.trim();
		if (trimmed.isEmpty())
		{
			throw ApiException.malformed("Enter the bingo server's address first.", null);
		}

		final HttpUrl url = HttpUrl.parse(trimmed);
		if (url == null)
		{
			throw ApiException.malformed("'" + trimmed + "' is not a valid address.", null);
		}
		if (!url.username().isEmpty() || !url.password().isEmpty()
			|| url.query() != null || url.fragment() != null)
		{
			throw ApiException.malformed("Enter a base URL without credentials, a query or a fragment.", null);
		}
		return url;
	}

	/** GET /api/plugin/game?code={joinCode} */
	public GameConfig fetchGame(HttpUrl baseUrl, String joinCode) throws ApiException
	{
		final HttpUrl url = path(baseUrl, "game")
			.newBuilder()
			.addQueryParameter("code", joinCode)
			.build();

		return execute(new Request.Builder().url(url).get().build(), GameConfig.class);
	}

	/**
	 * GET /api/plugin/board — bearer auth. Optional: a server that publishes no board answers
	 * 404 and the panel simply does not offer one.
	 *
	 * @return the board, or null if this server has none
	 */
	@Nullable
	public BoardView fetchBoard(HttpUrl baseUrl, String token) throws ApiException
	{
		try
		{
			return ResponseValidation.board(execute(new Request.Builder()
				.url(path(baseUrl, "board"))
				.header("Authorization", "Bearer " + token)
				.get()
				.build(), BoardView.class));
		}
		catch (ApiException e)
		{
			if (e.getStatus() == 404)
			{
				return null;
			}
			throw e;
		}
	}

	/** POST /api/plugin/sync — the join code is the credential. */
	public SyncResponse sync(HttpUrl baseUrl, SyncRequest request) throws ApiException
	{
		return execute(new Request.Builder()
			.url(path(baseUrl, "sync"))
			.post(RequestBody.create(JSON, gson.toJson(request)))
			.build(), SyncResponse.class);
	}

	/** POST /api/plugin/events — bearer auth. The body is always an array. */
	public EventBatchResponse postEvents(HttpUrl baseUrl, String token, List<EventEnvelope> events)
		throws ApiException
	{
		final String body = gson.toJson(events);
		if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES)
		{
			throw ApiException.malformed("The event batch exceeds the 2 MB request limit.", null);
		}
		return execute(new Request.Builder()
			.url(path(baseUrl, "events"))
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, body))
			.build(), EventBatchResponse.class);
	}

	/**
	 * POST /api/plugin/ready — bearer auth. Sent on sync and whenever readiness changes.
	 */
	public void postReady(HttpUrl baseUrl, String token, ReadinessReport report) throws ApiException
	{
		executeNoContent(new Request.Builder()
			.url(path(baseUrl, "ready"))
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, gson.toJson(report)))
			.build());
	}

	/** For requests whose success is the status alone; any response body is ignored. */
	private void executeNoContent(Request request) throws ApiException
	{
		execute(request, null);
	}

	private static HttpUrl path(HttpUrl baseUrl, String endpoint)
	{
		return baseUrl.newBuilder()
			.addPathSegments("api/plugin/" + endpoint)
			.build();
	}

	private <T> T execute(Request request, Class<T> type) throws ApiException
	{
		final Call call = openCall(request);
		try (Response response = call.execute())
		{
			final ResponseBody body = response.body();
			final String text = response.isSuccessful() && type == null ? "" : readBody(body);
			checkActive(call);

			if (!response.isSuccessful())
			{
				throw fail(response, text);
			}
			if (type == null)
			{
				return null;
			}

			final T parsed;
			try
			{
				final JsonElement json = gson.fromJson(text, JsonElement.class);
				if (json == null || !json.isJsonObject())
				{
					throw ApiException.malformed("The server did not return a JSON object.", null);
				}
				ResponseValidation.fields(json.getAsJsonObject(), type);
				parsed = gson.fromJson(json, type);
			}
			catch (JsonSyntaxException | IllegalStateException e)
			{
				throw ApiException.malformed("The server sent a response this plugin could not read.", e);
			}

			if (parsed == null)
			{
				throw ApiException.malformed("The server sent an empty response.", null);
			}
			return parsed;
		}
		catch (IOException e)
		{
			throw ApiException.transport("Could not reach " + request.url().host() + ".", e);
		}
		finally
		{
			synchronized (this)
			{
				calls.remove(call);
			}
		}
	}

	private synchronized Call openCall(Request request) throws ApiException
	{
		if (!enabled || !Long.valueOf(requestGeneration).equals(requestScope.get()))
		{
			throw ApiException.malformed("Network reporting is disabled.", null);
		}
		final Call call = okHttpClient.newCall(request);
		calls.add(call);
		return call;
	}

	private synchronized void checkActive(Call call) throws ApiException
	{
		if (!enabled || !calls.contains(call))
		{
			throw ApiException.malformed("The connection changed while the request was running.", null);
		}
	}

	private static String readBody(@Nullable ResponseBody body) throws IOException, ApiException
	{
		if (body == null)
		{
			return "";
		}
		if (body.contentLength() > MAX_BODY_BYTES)
		{
			throw ApiException.malformed("The server response exceeds the 2 MB limit.", null);
		}
		try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream())
		{
			final byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1)
			{
				if (output.size() + count > MAX_BODY_BYTES)
				{
					throw ApiException.malformed("The server response exceeds the 2 MB limit.", null);
				}
				output.write(buffer, 0, count);
			}
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private ApiException fail(Response response, String text)
	{
		final ApiError error = parseError(text);
		final int status = response.code();
		if (status == 400)
		{
			log.error("Bingo server rejected a request: {}", text.substring(0, Math.min(text.length(), 2048)));
		}

		if (status == 429)
		{
			return ApiException.rateLimited(retryAfterSeconds(response.header("Retry-After")), error);
		}
		if (status >= 500)
		{
			return ApiException.serverError(status, error);
		}
		return ApiException.client(status, error);
	}

	@Nullable
	private ApiError parseError(String text)
	{
		if (text.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(text, ApiError.class);
		}
		catch (JsonSyntaxException e)
		{
			// A server that fails without a readable body is still a failure; the status carries
			// enough to act on.
			log.debug("Could not read the error body: {}", text);
			return null;
		}
	}

	/** Retry-After is either a whole number of seconds or an HTTP date. */
	@Nullable
	static Long retryAfterSeconds(@Nullable String header)
	{
		if (header == null || header.trim().isEmpty())
		{
			return null;
		}

		final String value = header.trim();
		try
		{
			return Math.max(0L, Long.parseLong(value));
		}
		catch (NumberFormatException ignored)
		{
			// Not a number, so try the date form.
		}

		try
		{
			final long until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
				.toInstant().toEpochMilli();
			return Math.max(0L, (until - System.currentTimeMillis()) / 1000L);
		}
		catch (DateTimeParseException e)
		{
			log.debug("Unreadable Retry-After header: {}", value);
			return null;
		}
	}
}
