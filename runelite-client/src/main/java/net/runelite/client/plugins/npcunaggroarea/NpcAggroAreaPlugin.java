/*
 * Copyright (c) 2018, Woox <https://github.com/wooxsolo>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.npcunaggroarea;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.ItemID;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.geometry.Geometry;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.WildcardMatcher;

@Slf4j
@PluginDescriptor(
	name = "Unaggressive NPC timer",
	description = "Highlights the unaggressive area of NPCs nearby and timer until it becomes active",
	tags = {"highlight", "lines", "unaggro", "aggro", "aggressive", "npcs", "area", "timer", "slayer"}
)
public class NpcAggroAreaPlugin extends Plugin
{
	/*
	How it works: The game remembers 2 tiles. When the player goes >10 steps
	away from both tiles, the oldest one is moved to under the player and the
	NPC aggression timer resets.
	So to first figure out where the 2 tiles are, we wait until the player teleports
	a long enough distance. At that point it's very likely that the player
	moved out of the radius of both tiles, which resets one of them. The other
	should reset shortly after as the player starts moving around.
	*/

	private final static int SAFE_AREA_RADIUS = 10;
	private final static int UNKNOWN_AREA_RADIUS = SAFE_AREA_RADIUS * 2;
	private final static int AGGRESSIVE_TIME_SECONDS = 600;
	private final static Splitter NAME_SPLITTER = Splitter.on(CharMatcher.anyOf(",\n")).omitEmptyStrings().trimResults();

	@Inject
	private Client client;

	@Inject
	private NpcAggroAreaConfig config;

	@Inject
	private NpcAggroAreaOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	@Getter
	private WorldPoint[] safeCenters;

	@Getter
	private GeneralPath[] linesToDisplay;

	@Getter
	private boolean active;

	@Getter
	private AggressionTimer currentTimer;

	private WorldPoint lastPlayerLocation;
	private int currentPlane;
	private WorldPoint previousUnknownCenter;
	private boolean loggingIn;
	private List<String> npcNamePatterns;

	@Provides
	NpcAggroAreaConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NpcAggroAreaConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(overlay);
		safeCenters = new WorldPoint[2];
		linesToDisplay = new GeneralPath[Constants.MAX_Z];
		npcNamePatterns = NAME_SPLITTER.splitToList(config.npcNamePatterns());
	}

	@Override
	protected void shutDown() throws Exception
	{
		removeTimer();
		overlayManager.remove(overlay);
		safeCenters = null;
		linesToDisplay = null;
		lastPlayerLocation = null;
		currentTimer = null;
		loggingIn = false;
		npcNamePatterns = null;
		active = false;
	}

	private Area generateSafeArea()
	{
		Area area = new Area();

		for (WorldPoint wp : safeCenters)
		{
			if (wp == null)
			{
				continue;
			}

			Polygon poly = new Polygon();
			poly.addPoint(wp.getX() - SAFE_AREA_RADIUS, wp.getY() - SAFE_AREA_RADIUS);
			poly.addPoint(wp.getX() - SAFE_AREA_RADIUS, wp.getY() + SAFE_AREA_RADIUS + 1);
			poly.addPoint(wp.getX() + SAFE_AREA_RADIUS + 1, wp.getY() + SAFE_AREA_RADIUS + 1);
			poly.addPoint(wp.getX() + SAFE_AREA_RADIUS + 1, wp.getY() - SAFE_AREA_RADIUS);
			area.add(new Area(poly));
		}

		return area;
	}

	private boolean isOpenableAt(WorldPoint wp)
	{
		int sceneX = wp.getX() - client.getBaseX();
		int sceneY = wp.getY() - client.getBaseY();

		Tile tile = client.getScene().getTiles()[wp.getPlane()][sceneX][sceneY];
		if (tile == null)
		{
			return false;
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject == null)
		{
			return false;
		}

		ObjectComposition objectComposition = client.getObjectDefinition(wallObject.getId());
		if (objectComposition == null)
		{
			return false;
		}

		String[] actions = objectComposition.getActions();
		if (actions == null)
		{
			return false;
		}

		return Arrays.stream(actions).anyMatch(x -> x != null && x.toLowerCase().equals("open"));
	}

	private boolean collisionFilter(float[] p1, float[] p2)
	{
		int x1 = (int)p1[0];
		int y1 = (int)p1[1];
		int x2 = (int)p2[0];
		int y2 = (int)p2[1];

		if (x1 > x2)
		{
			int temp = x1;
			x1 = x2;
			x2 = temp;
		}
		if (y1 > y2)
		{
			int temp = y1;
			y1 = y2;
			y2 = temp;
		}
		int dx = x2 - x1;
		int dy = y2 - y1;
		WorldArea wa1 = new WorldArea(new WorldPoint(
			x1, y1, currentPlane), 1, 1);
		WorldArea wa2 = new WorldArea(new WorldPoint(
			x1 - dy, y1 - dx, currentPlane), 1, 1);

		if (isOpenableAt(wa1.toWorldPoint()) || isOpenableAt(wa2.toWorldPoint()))
		{
			// When there's something with the open option (e.g. a door) on the tile,
			// we assume it can be opened and walked through afterwards. Without this
			// check, the line for that tile wouldn't render with collision detection
			// because the collision check isn't done if collision data changes.
			return true;
		}

		boolean b1 = wa1.canTravelInDirection(client, -dy, -dx);
		boolean b2 = wa2.canTravelInDirection(client, dy, dx);
		return b1 && b2;
	}

	private void transformWorldToLocal(float[] coords)
	{
		LocalPoint lp = LocalPoint.fromWorld(client, (int)coords[0], (int)coords[1]);
		coords[0] = lp.getX() - Perspective.LOCAL_TILE_SIZE / 2;
		coords[1] = lp.getY() - Perspective.LOCAL_TILE_SIZE / 2;
	}

	private void reevaluateActive()
	{
		if (currentTimer != null)
		{
			currentTimer.setVisible(active && config.showTimer());
		}
		calculateLinesToDisplay();
	}

	private void calculateLinesToDisplay()
	{
		if (active && config.showAreaLines())
		{
			Rectangle sceneRect = new Rectangle(
				client.getBaseX() + 1, client.getBaseY() + 1,
				Constants.SCENE_SIZE - 2, Constants.SCENE_SIZE - 2);

			for (int i = 0; i < linesToDisplay.length; i++)
			{
				currentPlane = i;

				GeneralPath lines = new GeneralPath(generateSafeArea());
				lines = Geometry.clipPath(lines, sceneRect);
				lines = Geometry.unitifyPath(lines, 1);
				if (config.collisionDetection())
				{
					lines = Geometry.filterPath(lines, this::collisionFilter);
				}
				lines = Geometry.transformPath(lines, this::transformWorldToLocal);
				linesToDisplay[i] = lines;
			}
		}
		else
		{
			for (int i = 0; i < linesToDisplay.length; i++)
			{
				linesToDisplay[i] = null;
			}
		}
	}

	private void removeTimer()
	{
		if (currentTimer != null)
		{
			infoBoxManager.removeInfoBox(currentTimer);
			currentTimer = null;
		}
	}

	private void createTimer(Duration duration)
	{
		removeTimer();

		BufferedImage image = itemManager.getImage(ItemID.ENSOULED_DEMON_HEAD);
		currentTimer = new AggressionTimer(duration, image, this, active && config.showTimer());
		infoBoxManager.addInfoBox(currentTimer);
	}

	private void resetTimer()
	{
		createTimer(Duration.ofSeconds(AGGRESSIVE_TIME_SECONDS));
	}

	private boolean isNpcMatch(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			return false;
		}

		// Most NPCs stop aggroing when the player has more than double
		// its combat level.
		int playerLvl = client.getLocalPlayer().getCombatLevel();
		int npcLvl = composition.getCombatLevel();
		String npcName = composition.getName().toLowerCase();
		if (npcLvl > 0 && playerLvl > npcLvl * 2)
		{
			return false;
		}

		for (String pattern : npcNamePatterns)
		{
			if (WildcardMatcher.matches(pattern, npcName))
			{
				return true;
			}
		}

		return false;
	}

	private void recheckActive()
	{
		active = config.alwaysActive();

		if (!active)
		{
			for (NPC npc : client.getNpcs())
			{
				if (isNpcMatch(npc))
				{
					active = true;
					break;
				}
			}
		}

		reevaluateActive();
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (config.alwaysActive())
		{
			return;
		}

		if (!active && isNpcMatch(event.getNpc()))
		{
			active = true;
			reevaluateActive();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		WorldPoint newLocation = client.getLocalPlayer().getWorldLocation();
		if (lastPlayerLocation != null)
		{
			if (safeCenters[1] == null && newLocation.distanceTo2D(lastPlayerLocation) > SAFE_AREA_RADIUS * 4)
			{
				safeCenters[0] = null;
				safeCenters[1] = newLocation;
				resetTimer();
				calculateLinesToDisplay();

				// We don't know where the previous area was, so if the player e.g.
				// entered a dungeon and then goes back out, he/she may enter the previous
				// area which is unknown and would make the plugin inaccurate
				previousUnknownCenter = lastPlayerLocation;
			}
		}

		if (safeCenters[0] == null && previousUnknownCenter != null &&
			previousUnknownCenter.distanceTo2D(newLocation) <= UNKNOWN_AREA_RADIUS)
		{
			// Player went back to their previous unknown area before the 2nd
			// center point was found, which means we don't know where it is again.
			safeCenters[1] = null;
			removeTimer();
			calculateLinesToDisplay();
		}

		if (safeCenters[1] != null)
		{
			if (Arrays.stream(safeCenters).noneMatch(
				x -> x != null && x.distanceTo2D(newLocation) <= SAFE_AREA_RADIUS))
			{
				safeCenters[0] = safeCenters[1];
				safeCenters[1] = newLocation;
				resetTimer();
				calculateLinesToDisplay();
				previousUnknownCenter = null;
			}
		}

		lastPlayerLocation = newLocation;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		String key = event.getKey();
		switch (key)
		{
			case "npcUnaggroAlwaysActive":
				recheckActive();
				break;
			case "npcUnaggroShowTimer":
				if (currentTimer != null)
				{
					currentTimer.setVisible(active && config.showTimer());
				}
				break;
			case "npcUnaggroCollisionDetection":
			case "npcUnaggroShowAreaLines":
				calculateLinesToDisplay();
				break;
			case "npcUnaggroNames":
				npcNamePatterns = NAME_SPLITTER.splitToList(config.npcNamePatterns());
				recheckActive();
				break;
		}
	}

	private void loadConfig()
	{
		safeCenters[0] = configManager.getConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_CENTER1, WorldPoint.class);
		safeCenters[1] = configManager.getConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_CENTER2, WorldPoint.class);
		lastPlayerLocation = configManager.getConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_LOCATION, WorldPoint.class);

		Duration timeLeft = configManager.getConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_DURATION, Duration.class);
		if (timeLeft != null)
		{
			createTimer(timeLeft);
		}
	}

	private void resetConfig()
	{
		configManager.unsetConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_CENTER1);
		configManager.unsetConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_CENTER2);
		configManager.unsetConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_LOCATION);
		configManager.unsetConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_DURATION);
	}

	private void saveConfig()
	{
		if (safeCenters[0] == null || safeCenters[1] == null || lastPlayerLocation == null || currentTimer == null)
		{
			resetConfig();
		}
		else
		{
			configManager.setConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_CENTER1, safeCenters[0]);
			configManager.setConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_CENTER2, safeCenters[1]);
			configManager.setConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_LOCATION, lastPlayerLocation);
			configManager.setConfiguration(NpcAggroAreaConfig.CONFIG_GROUP, NpcAggroAreaConfig.CONFIG_DURATION, Duration.between(Instant.now(), currentTimer.getEndTime()));
		}
	}

	private void onLogin()
	{
		loadConfig();
		resetConfig();

		WorldPoint newLocation = client.getLocalPlayer().getWorldLocation();
		assert newLocation != null;

		// If the player isn't at the location he/she logged out at,
		// the safe unaggro area probably changed, and should be disposed.
		if (lastPlayerLocation == null || newLocation.distanceTo(lastPlayerLocation) != 0)
		{
			safeCenters[0] = null;
			safeCenters[1] = null;
			lastPlayerLocation = newLocation;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			if (loggingIn)
			{
				loggingIn = false;
				onLogin();
			}

			recheckActive();
		}
		else if (event.getGameState() == GameState.LOGGING_IN)
		{
			loggingIn = true;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			if (lastPlayerLocation != null)
			{
				saveConfig();
			}

			safeCenters[0] = null;
			safeCenters[1] = null;
			lastPlayerLocation = null;
		}
	}
}
