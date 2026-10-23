package com.millman97.competitivebingo.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.millman97.competitivebingo.model.BoardCell;
import com.millman97.competitivebingo.model.BoardLimits;
import com.millman97.competitivebingo.model.BoardSection;
import com.millman97.competitivebingo.model.BoardView;
import com.millman97.competitivebingo.model.EventBatchResponse;
import com.millman97.competitivebingo.model.GameConfig;
import com.millman97.competitivebingo.model.Protocol;
import com.millman97.competitivebingo.model.SyncResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Checks protocol invariants before a response is used as connection or UI state. */
final class ResponseValidation
{
	private ResponseValidation()
	{
	}

	static void fields(JsonObject json, Class<?> type) throws ApiException
	{
		if (type == GameConfig.class)
		{
			strings(json, "gameId", "name", "state", "minPluginVersion");
			numbers(json, "startsAt", "endsAt", "schemaVersion");
			require(json.get("schemaVersion").getAsLong() == Protocol.SCHEMA_VERSION,
				"That server uses a different schema version. Update the plugin.");
			bool(json, "screenshotsRequested");
			array(json, "trackedMetrics");
			final JsonElement rules = json.get("dropValueRules");
			if (rules != null && !rules.isJsonNull())
			{
				require(rules.isJsonArray(), "dropValueRules must be an array.");
				for (JsonElement rule : rules.getAsJsonArray())
				{
					require(rule.isJsonObject(), "A drop value rule must be an object.");
					enumValue(rule.getAsJsonObject(), "valuation", "GRAND_EXCHANGE", "HIGH_ALCHEMY");
					enumValue(rule.getAsJsonObject(), "counts", "ITEMS", "VALUE");
				}
			}
		}
		else if (type == SyncResponse.class)
		{
			strings(json, "playerToken", "gameId");
			numbers(json, "tokenExpiresAt");
		}
		else if (type == BoardView.class)
		{
			strings(json, "title");
			numbers(json, "capturedAt");
			require(json.has("sections"), "The board is missing sections.");
		}
		else if (type == EventBatchResponse.class)
		{
			array(json, "results");
			require(json.getAsJsonArray("results").size() <= 50, "Too many event results.");
			final HashSet<String> eventIds = new HashSet<>();
			for (JsonElement result : json.getAsJsonArray("results"))
			{
				require(result.isJsonObject(), "An event result must be an object.");
				final JsonObject entry = result.getAsJsonObject();
				strings(entry, "eventId");
				require(eventIds.add(entry.get("eventId").getAsString()), "Duplicate event result.");
				bool(entry, "accepted");
				bool(entry, "duplicate");
			}
		}
	}

	static void game(GameConfig game) throws ApiException
	{
		require(game != null, "The server sent no game.");
		text(game.getGameId());
		text(game.getName());
		require(!game.getGameId().trim().isEmpty() && !game.getName().trim().isEmpty(), "The game ID and name must not be empty.");
		require(game.getState() != null, "The server sent an unknown or missing game state.");
		require(game.getStartsAt() >= 0 && game.getEndsAt() >= game.getStartsAt(), "The game times are invalid.");
		require(game.getSchemaVersion() == Protocol.SCHEMA_VERSION, "That server uses a different schema version. Update the plugin.");
		require(game.getMinPluginVersion() != null
			&& game.getMinPluginVersion().matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}"), "The server sent an invalid minimum plugin version.");
		require(!SyncService.isOlder(Protocol.PLUGIN_VERSION, game.getMinPluginVersion()),
			"That game requires plugin version " + game.getMinPluginVersion() + ". Update the plugin.");
		require(game.getTrackedMetrics() != null && game.getTrackedMetrics().size() <= 256,
			"The game must list at most 256 tracked metrics.");
		require(new HashSet<>(game.getTrackedMetrics()).size() == game.getTrackedMetrics().size(), "The tracked metric list contains duplicates.");
		if (game.getMetricLabels() != null)
		{
			require(game.getMetricLabels().size() <= 288, "Too many metric labels.");
			for (String label : game.getMetricLabels().values())
			{
				text(label);
			}
		}
		if (game.getMetricTargets() != null)
		{
			require(game.getMetricTargets().size() <= 288, "Too many metric targets.");
			for (Long target : game.getMetricTargets().values())
			{
				require(target != null && target > 0, "A metric target must be positive.");
			}
		}
		if (game.getBlockedWorldTypes() != null)
		{
			require(game.getBlockedWorldTypes().size() <= 64, "Too many blocked world types.");
			for (String name : game.getBlockedWorldTypes())
			{
				require(name != null && name.matches("[A-Z][A-Z0-9_]{0,63}"), "Invalid blocked world type.");
			}
		}
		require(game.getStartWorld() == null || game.getStartWorld() > 0, "Invalid start world.");
	}

	static void sync(SyncResponse response, GameConfig game) throws ApiException
	{
		require(response != null && game.getGameId().equals(response.getGameId()), "The sync response belongs to a different game. Press Sync again.");
		require(response.getPlayerToken() != null && !response.getPlayerToken().trim().isEmpty()
			&& response.getPlayerToken().length() <= 4096
			&& response.getPlayerToken().chars().allMatch(c -> c >= 33 && c <= 126), "The server sent an invalid player token.");
		require(response.getTokenExpiresAt() > System.currentTimeMillis(), "The server sent an expired player token. Press Sync again.");
		if (response.getTeamName() != null)
		{
			text(response.getTeamName());
		}
	}

	static BoardView board(BoardView board) throws ApiException
	{
		text(board.getTitle());
		require(!board.getTitle().trim().isEmpty(), "The board title is empty.");
		if (board.getSubtitle() != null)
		{
			text(board.getSubtitle());
		}
		require(board.getCapturedAt() >= 0, "Invalid board timestamp.");
		require(board.getSections().size() <= BoardLimits.MAX_SECTIONS, "The board has too many sections.");
		final List<BoardSection> sections = new ArrayList<>();
		long squares = 0;
		for (BoardSection section : board.getSections())
		{
			require(section != null && section.getKind() != null, "The board contains a missing section kind.");
			if (section.getTitle() != null) { text(section.getTitle()); }
			if (section.getBody() != null) { text(section.getBody()); }
			int rows = section.getRows() == null ? 0 : section.getRows();
			int cols = section.getCols() == null ? 0 : section.getCols();
			require(rows >= 0 && cols >= 0, "Negative board dimensions.");
			require(section.getCells().size() <= BoardLimits.MAX_CELLS, "Too many board cells.");
			for (BoardCell cell : section.getCells())
			{
				require(cell != null, "The board contains a null cell.");
				text(cell.getTitle());
				if (cell.getTooltip() != null) { text(cell.getTooltip()); }
				require(cell.getLines().size() <= BoardLimits.MAX_LINES, "Too many lines in a board cell.");
				for (String line : cell.getLines()) { text(line); }
				if (cell.getRow() != null)
				{
					require(cell.getRow() >= 0 && cell.getRow() < BoardLimits.MAX_DIMENSION, "Board row is outside the supported range.");
					rows = Math.max(rows, cell.getRow() + 1);
				}
				if (cell.getCol() != null)
				{
					require(cell.getCol() >= 0 && cell.getCol() < BoardLimits.MAX_DIMENSION, "Board column is outside the supported range.");
					cols = Math.max(cols, cell.getCol() + 1);
				}
			}
			require(rows <= BoardLimits.MAX_DIMENSION && cols <= BoardLimits.MAX_DIMENSION, "The board dimensions are too large.");
			squares += BoardSection.GRID.equalsIgnoreCase(section.getKind()) ? (long) rows * cols : section.getCells().size();
			require(squares <= BoardLimits.MAX_CELLS, "The board has too many squares.");
			sections.add(new BoardSection(section.getKind(), section.getTitle(), rows, cols,
				section.getCells(), section.getBody()));
		}
		return new BoardView(board.getTitle(), board.getSubtitle(), board.getCapturedAt(), sections);
	}

	private static void strings(JsonObject json, String... names) throws ApiException
	{
		for (String name : names)
		{
			final JsonElement value = json.get(name);
			require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Missing or invalid " + name + ".");
		}
	}

	private static void numbers(JsonObject json, String... names) throws ApiException
	{
		for (String name : names)
		{
			final JsonElement value = json.get(name);
			require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Missing or invalid " + name + ".");
			try { value.getAsBigDecimal().longValueExact(); }
			catch (ArithmeticException | NumberFormatException e) { throw ApiException.malformed("Invalid integer " + name + ".", e); }
		}
	}

	private static void bool(JsonObject json, String name) throws ApiException
	{
		final JsonElement value = json.get(name);
		require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Missing or invalid " + name + ".");
	}

	private static void array(JsonObject json, String name) throws ApiException
	{
		require(json.has(name) && json.get(name).isJsonArray(), "Missing or invalid " + name + ".");
	}

	private static void enumValue(JsonObject json, String name, String... allowed) throws ApiException
	{
		final JsonElement value = json.get(name);
		if (value == null || value.isJsonNull()) { return; }
		strings(json, name);
		for (String candidate : allowed)
		{
			if (candidate.equals(value.getAsString())) { return; }
		}
		throw ApiException.malformed("Unknown " + name + ": " + value.getAsString(), null);
	}

	private static void text(String value) throws ApiException
	{
		require(value != null && value.length() <= Protocol.MAX_TEXT_LENGTH, "A response text field is missing or too long.");
	}

	private static void require(boolean condition, String message) throws ApiException
	{
		if (!condition) { throw ApiException.malformed(message, null); }
	}
}
