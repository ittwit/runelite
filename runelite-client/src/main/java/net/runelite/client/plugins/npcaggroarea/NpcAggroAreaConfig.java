package net.runelite.client.plugins.npcaggroarea;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("npcAggroArea")
public interface NpcAggroAreaConfig extends Config
{
	@ConfigItem(
		keyName = "showNpcAggroArea",
		name = "Show area",
		description = "Show NPC aggro area",
		position = 1
	)
	default boolean showNpcAggroArea()
	{
		return false;
	}

	@ConfigItem(
		keyName = "npcAggroAreaCollisionDetection",
		name = "Collision detection",
		description = "Only show lines where they can be walked through",
		position = 2
	)
	default boolean collisionDetection()
	{
		return true;
	}

	@ConfigItem(
		keyName = "npcAggroAreaColor",
		name = "Aggro area color",
		description = "Choose color to use for marking aggro area",
		position = 3
	)
	default Color aggroAreaColor()
	{
		return Color.RED;
	}
}
