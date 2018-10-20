package net.runelite.client.plugins.npcaggroarea;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.GeneralPath;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.geometry.Geometry;
import net.runelite.client.plugins.zoneindicators.ZoneVisibility;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

public class NpcAggroAreaOverlay extends Overlay
{
	private final static int MAX_LOCAL_DRAW_LENGTH = 20 * Perspective.LOCAL_TILE_SIZE;

	@Inject
	private Client client;

	@Inject
	private NpcAggroAreaConfig config;

	@Inject
	private NpcAggroAreaPlugin plugin;

	@Inject
	public NpcAggroAreaOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.LOW);
	}

	private void renderPath(Graphics2D graphics, GeneralPath path, Color color)
	{
		LocalPoint playerLp = client.getLocalPlayer().getLocalLocation();
		Rectangle viewArea = new Rectangle(
			playerLp.getX() - MAX_LOCAL_DRAW_LENGTH,
			playerLp.getY() - MAX_LOCAL_DRAW_LENGTH,
			MAX_LOCAL_DRAW_LENGTH * 2,
			MAX_LOCAL_DRAW_LENGTH * 2);

		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(2));

		path = Geometry.clipPath(path, viewArea);
		path = Geometry.filterPath(path, (p1, p2) ->
			Perspective.localToCanvas(client, new LocalPoint((int)p1[0], (int)p1[1]), client.getPlane()) != null &&
			Perspective.localToCanvas(client, new LocalPoint((int)p2[0], (int)p2[1]), client.getPlane()) != null);
		path = Geometry.transformPath(path, coords ->
		{
			Point point = Perspective.localToCanvas(client, new LocalPoint((int)coords[0], (int)coords[1]), client.getPlane());
			coords[0] = point.getX();
			coords[1] = point.getY();
		});

		graphics.draw(path);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showNpcAggroArea())
		{
			return null;
		}

		GeneralPath lines = plugin.getLinesToDisplay()[client.getPlane()];
		if (lines == null)
		{
			return null;
		}

		renderPath(graphics, lines, config.aggroAreaColor());

		return null;
	}
}
