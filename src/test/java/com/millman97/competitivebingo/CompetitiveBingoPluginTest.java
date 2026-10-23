package com.millman97.competitivebingo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CompetitiveBingoPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CompetitiveBingoPlugin.class);
		RuneLite.main(args);
	}
}
