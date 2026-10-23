package com.millman97.competitivebingo.sync;

import com.millman97.competitivebingo.model.ApiError;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * A request that did not succeed.
 *
 * {@link #isRetryable()} separates transient network trouble, which is retried, from everything
 * else, which fails immediately.
 */
@Getter
public class ApiException extends Exception
{
	/** HTTP status, or 0 when the request never got a response at all. */
	private final int status;

	/** The parsed error body, when the server sent one that could be read. */
	@Nullable
	private final ApiError error;

	private final boolean retryable;

	/** Seconds to wait, from a 429's Retry-After header. Null when the server named no delay. */
	@Nullable
	private final Long retryAfterSeconds;

	private ApiException(String message, int status, @Nullable ApiError error, boolean retryable,
		@Nullable Long retryAfterSeconds, @Nullable Throwable cause)
	{
		super(message, cause);
		this.status = status;
		this.error = error;
		this.retryable = retryable;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	/** Connection refused, DNS failure, timeout — the request never reached a server. */
	public static ApiException transport(String message, Throwable cause)
	{
		return new ApiException(message, 0, null, true, null, cause);
	}

	/** A 5xx. Server issues, worth trying again. */
	public static ApiException serverError(int status, @Nullable ApiError error)
	{
		return new ApiException("Server error " + status, status, error, true, null, null);
	}

	/** A 429. A scheduled resume at the time the server named. */
	public static ApiException rateLimited(@Nullable Long retryAfterSeconds, @Nullable ApiError error)
	{
		return new ApiException("Rate limited", 429, error, true, retryAfterSeconds, null);
	}

	/** Any other 4xx. The request was wrong and sending it again will not make it right. */
	public static ApiException client(int status, @Nullable ApiError error)
	{
		return new ApiException("Request failed with " + status, status, error, false, null, null);
	}

	/** The server answered with something unreadable. */
	public static ApiException malformed(String message, @Nullable Throwable cause)
	{
		return new ApiException(message, 0, null, false, null, cause);
	}

	/**
	 * What to show the player. Prefers the server's own wording.
	 */
	public String playerMessage()
	{
		if (error != null && error.getMessage() != null && !error.getMessage().isEmpty())
		{
			return error.getMessage().substring(0, Math.min(error.getMessage().length(), 1024));
		}

		switch (status)
		{
			case 0:
				return getMessage();
			case 401:
				return "Your session expired. Press Sync again.";
			case 403:
				return "You are not on a team in this game.";
			case 404:
				return "That join code doesn't match any game.";
			case 409:
				return "This plugin version is not accepted by that server."
					+ (error == null || error.getRequiredVersion() == null ? ""
						: " Required version: " + error.getRequiredVersion() + ".");
			case 410:
				return "That game has ended.";
			case 429:
				return "The server asked us to slow down.";
			default:
				return getMessage();
		}
	}
}
