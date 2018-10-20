package net.runelite.client.plugins.npcaggroarea;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.util.Arrays;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
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
import net.runelite.api.geometry.Geometry;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "NPC aggro area",
	description = "Highlight aggro area of NPCs",
	tags = {"highlight", "aggro", "aggressive", "npcs", "area"}
)
public class NpcAggroAreaPlugin extends Plugin
{
	private final static int AGGRO_AREA_RADIUS = 10;

	@Inject
	private Client client;

	@Inject
	private NpcAggroAreaConfig config;

	@Inject
	private NpcAggroAreaOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	@Getter
	private WorldPoint[] safeCenters;

	@Getter
	private GeneralPath[] linesToDisplay;

	private WorldPoint lastPlayerLocation;
	private int currentPlane;

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
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		safeCenters = null;
		linesToDisplay = null;
		lastPlayerLocation = null;
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
			poly.addPoint(wp.getX() - AGGRO_AREA_RADIUS, wp.getY() - AGGRO_AREA_RADIUS);
			poly.addPoint(wp.getX() - AGGRO_AREA_RADIUS, wp.getY() + AGGRO_AREA_RADIUS + 1);
			poly.addPoint(wp.getX() + AGGRO_AREA_RADIUS + 1, wp.getY() + AGGRO_AREA_RADIUS + 1);
			poly.addPoint(wp.getX() + AGGRO_AREA_RADIUS + 1, wp.getY() - AGGRO_AREA_RADIUS);
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

	private void calculateLinesToDisplay()
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

	@Subscribe
	public void onGameTick(GameTick event)
	{
		WorldPoint newLocation = client.getLocalPlayer().getWorldLocation();
		if (lastPlayerLocation != null)
		{
			if (safeCenters[1] == null && newLocation.distanceTo2D(lastPlayerLocation) > AGGRO_AREA_RADIUS * 4)
			{
				safeCenters[1] = newLocation;
				calculateLinesToDisplay();
			}
		}

		if (safeCenters[1] != null)
		{
			boolean inRange = false;
			for (WorldPoint wp : safeCenters)
			{
				if (wp == null)
				{
					continue;
				}

				if (wp.distanceTo2D(newLocation) <= AGGRO_AREA_RADIUS)
				{
					inRange = true;
					break;
				}
			}
			if (!inRange)
			{
				safeCenters[0] = safeCenters[1];
				safeCenters[1] = newLocation;
				calculateLinesToDisplay();
			}
		}

		lastPlayerLocation = newLocation;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getKey().equals("npcAggroAreaCollisionDetection") ||
			event.getKey().equals("showNpcAggroArea"))
		{
			calculateLinesToDisplay();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			calculateLinesToDisplay();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			safeCenters[0] = null;
			safeCenters[1] = null;
			lastPlayerLocation = null;
		}
	}
}
