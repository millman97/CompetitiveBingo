package com.millman97.competitivebingo.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventEnvelopeSerialisationTest
{
	private final Gson gson = Protocol.wireGson(new Gson());

	private EventEnvelope.EventEnvelopeBuilder base()
	{
		return EventEnvelope.builder().schemaVersion(Protocol.SCHEMA_VERSION).eventId("event")
			.gameId("game").accountHash("-9007199254740993").sessionId("session")
			.worldTypes(Collections.singletonList("MEMBERS")).source(EventSource.OBSERVED);
	}

	@Test
	public void observedEventsPreserveIdentityNullsAndRuntimeDetailFields()
	{
		final JsonObject json = gson.toJsonTree(base()
			.metric(new MetricObservation("drop.abyssal_whip", MetricType.DROP, null, 2L, true))
			.detail(new DropDetail(4151, "Abyssal whip", 2, "Abyssal demon", "NPC", null,
				null, null, null, 0)).build()).getAsJsonObject();
		assertEquals(Protocol.SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
		assertTrue(json.get("accountHash").getAsJsonPrimitive().isString());
		assertEquals("-9007199254740993", json.get("accountHash").getAsString());
		assertTrue(json.get("screenshot").isJsonNull());
		assertTrue(json.getAsJsonObject("metric").get("value").isJsonNull());
		assertEquals(2L, json.getAsJsonObject("metric").get("delta").getAsLong());
		assertEquals(4151, json.getAsJsonObject("detail").get("itemId").getAsInt());
		assertTrue(json.getAsJsonObject("detail").get("killCount").isJsonNull());
		assertEquals("MEMBERS", json.getAsJsonArray("worldTypes").get(0).getAsString());
	}

	@Test
	public void manualEvidenceKeepsTheOriginalInputAndScreenshot()
	{
		final JsonObject json = gson.toJsonTree(base().source(EventSource.MANUAL)
			.metric(new MetricObservation("time.vorkath", MetricType.DURATION, null, null, true))
			.detail(new ManualDetail("Vorkath", "See attached evidence", null)).build()
			.withScreenshot("QUJD", "image/jpeg")).getAsJsonObject();
		assertEquals("MANUAL", json.get("source").getAsString());
		assertEquals("See attached evidence", json.getAsJsonObject("detail").get("input").getAsString());
		assertTrue(json.getAsJsonObject("metric").get("delta").isJsonNull());
		assertEquals("QUJD", json.get("screenshot").getAsString());
		assertEquals("image/jpeg", json.get("screenshotMediaType").getAsString());
	}
}
