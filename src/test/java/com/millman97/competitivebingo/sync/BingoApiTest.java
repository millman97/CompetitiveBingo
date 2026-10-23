package com.millman97.competitivebingo.sync;

import com.google.gson.Gson;
import com.millman97.competitivebingo.model.ApiError;
import com.millman97.competitivebingo.model.BoardView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.*;

public class BingoApiTest
{
	private final AtomicInteger intercepted = new AtomicInteger();
	private final AtomicReference<String> body = new AtomicReference<>("{}");
	private final BingoApi api = new BingoApi(new OkHttpClient.Builder().addInterceptor(chain -> {
		intercepted.incrementAndGet();
		return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
			.code(200).message("Mocked").body(ResponseBody.create(MediaType.get("application/json"), body.get())).build();
	}).build(), new Gson());

	@Test
	public void disabledRequestsNeverReachTheHttpClient()
	{
		assertNotNull(fetch(api.getRequestGeneration()));
		assertEquals(0, intercepted.get());
	}

	@Test
	public void staleWorkCannotOpenARequestAfterReenabling()
	{
		api.setEnabled(true);
		final long old = api.getRequestGeneration();
		api.setEnabled(false);
		api.setEnabled(true);
		assertNotNull(fetch(old));
		assertEquals(0, intercepted.get());
	}

	@Test
	public void requiredFieldsAreCheckedBeforeGsonDefaultsCanApply()
	{
		api.setEnabled(true);
		assertTrue(fetch(api.getRequestGeneration()).playerMessage().contains("gameId"));
		assertEquals(1, intercepted.get());
	}

	@Test
	public void largeResponsesAreRejectedLocally()
	{
		body.set("x".repeat(BingoApi.MAX_BODY_BYTES + 1));
		api.setEnabled(true);
		assertTrue(fetch(api.getRequestGeneration()).playerMessage().contains("2 MB"));
		assertEquals(1, intercepted.get());
	}

	@Test
	public void malformedErrorsKeepTheActionableExplanation()
	{
		assertEquals("Unsupported metric", ApiException.malformed("Unsupported metric", null).playerMessage());
		assertTrue(ApiException.client(409, new ApiError("VERSION", null, "2.0.0"))
			.playerMessage().contains("2.0.0"));
	}

	@Test
	public void boardDimensionsAreNormalizedBeforePublication()
	{
		body.set("{\"title\":\"Test\",\"capturedAt\":0,\"sections\":[{\"kind\":\"GRID\","
			+ "\"cells\":[{\"row\":2,\"col\":3,\"title\":\"Tile\"}]}]}");
		api.setEnabled(true);
		final AtomicReference<BoardView> result = new AtomicReference<>();
		api.withGeneration(api.getRequestGeneration(), () -> {
			try { result.set(api.fetchBoard(HttpUrl.get("https://mock.invalid/"), "token")); }
			catch (ApiException error) { throw new AssertionError(error); }
		});
		assertEquals(Integer.valueOf(3), result.get().getSections().get(0).getRows());
		assertEquals(Integer.valueOf(4), result.get().getSections().get(0).getCols());
	}

	@Test
	public void invalidBoardsAreRejectedAtTheApiBoundary()
	{
		api.setEnabled(true);
		for (String section : new String[]{
			"{\"kind\":\"GRID\",\"rows\":2147483647,\"cols\":1}",
			"{\"kind\":\"GRID\",\"rows\":1,\"cols\":1,\"cells\":[{\"row\":2147483647,\"col\":0,\"title\":\"Far away\"}]}",
			"{\"kind\":\"GRID\",\"rows\":50,\"cols\":50}",
			"{\"kind\":\"GRID\",\"cells\":[null]}",
			"{\"cells\":[]}"
		})
		{
			body.set("{\"title\":\"Test\",\"capturedAt\":0,\"sections\":[" + section + "]}");
			final AtomicReference<ApiException> failure = new AtomicReference<>();
			api.withGeneration(api.getRequestGeneration(), () -> {
				try { api.fetchBoard(HttpUrl.get("https://mock.invalid/"), "token"); }
				catch (ApiException error) { failure.set(error); }
			});
			assertNotNull("Invalid board was published: " + section, failure.get());
		}
	}

	private ApiException fetch(long expected)
	{
		final AtomicReference<ApiException> error = new AtomicReference<>();
		api.withGeneration(expected, () -> {
			try { api.fetchGame(HttpUrl.get("https://mock.invalid/"), "code"); }
			catch (ApiException failure) { error.set(failure); }
		});
		return error.get();
	}
}
