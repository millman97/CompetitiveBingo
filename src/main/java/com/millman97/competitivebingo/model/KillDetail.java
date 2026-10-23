package com.millman97.competitivebingo.model;

import javax.annotation.Nullable;
import lombok.Value;

/** Detail for a server-attributed NPC kill that has no authoritative game counter. */
@Value
public class KillDetail implements EventDetail
{
	int npcId;

	String npcName;

	@Nullable
	Integer combatLevel;

	String signal;

	/** Distinguishes multiple accepted kills with the same NPC ID during one game tick. */
	int observationOrdinal;
}
