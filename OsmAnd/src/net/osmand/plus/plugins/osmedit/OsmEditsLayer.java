package net.osmand.plus.plugins.osmedit;

import static net.osmand.data.FavouritePoint.DEFAULT_BACKGROUND_TYPE;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.os.AsyncTask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.osmand.core.android.MapRendererView;
import net.osmand.core.jni.MapMarker;
import net.osmand.core.jni.MapMarkerBuilder;
import net.osmand.core.jni.MapMarkersCollection;
import net.osmand.core.jni.PointI;
import net.osmand.core.jni.TextRasterizer;
import net.osmand.data.BackgroundType;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.RotatedTileBox;
import net.osmand.osm.PoiType;
import net.osmand.osm.edit.Entity;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.AppInitializer.InitEvents;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.plugins.osmedit.asynctasks.SaveOsmChangeAsyncTask;
import net.osmand.plus.plugins.osmedit.asynctasks.SaveOsmNoteAsyncTask;
import net.osmand.plus.plugins.osmedit.data.OpenstreetmapPoint;
import net.osmand.plus.plugins.osmedit.data.OsmNotesPoint;
import net.osmand.plus.plugins.osmedit.data.OsmPoint;
import net.osmand.plus.plugins.osmedit.helpers.OpenstreetmapLocalUtil;
import net.osmand.plus.plugins.osmedit.helpers.OsmBugsLocalUtil;
import net.osmand.plus.render.RenderingIcons;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.PointImageDrawable;
import net.osmand.plus.views.layers.ContextMenuLayer;
import net.osmand.plus.views.layers.ContextMenuLayer.ApplyMovedObjectCallback;
import net.osmand.plus.views.layers.ContextMenuLayer.IContextMenuProvider;
import net.osmand.plus.views.layers.ContextMenuLayer.IMoveObjectProvider;
import net.osmand.plus.views.layers.MapTextLayer;
import net.osmand.plus.views.layers.MapTextLayer.MapTextProvider;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OsmEditsLayer extends OsmandMapLayer implements IContextMenuProvider, IMoveObjectProvider,
		MapTextProvider<OpenstreetmapPoint> {

	private static final int START_ZOOM = 10;

	private final OsmandApplication app;
	private final OsmEditingPlugin plugin;
	private final Context ctx;
	private final OpenstreetmapLocalUtil mOsmChangeUtil;
	private final OsmBugsLocalUtil mOsmBugsUtil;

	private final List<OsmPoint> drawnOsmEdits = new ArrayList<>();

	private ContextMenuLayer contextMenuLayer;
	private MapTextLayer mapTextLayer;

	//OpenGL
	private boolean nightMode;
	private float storedTextScale = 1.0f;
	private boolean poiTypesInitialized;

	public OsmEditsLayer(@NonNull Context context, @NonNull OsmEditingPlugin plugin) {
		super(context);
		this.ctx = context;
		this.plugin = plugin;
		app = getApplication();
		mOsmChangeUtil = plugin.getPoiModificationLocalUtil();
		mOsmBugsUtil = plugin.getOsmNotesLocalUtil();
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);

		contextMenuLayer = view.getLayerByClass(ContextMenuLayer.class);
		mapTextLayer = view.getLayerByClass(MapTextLayer.class);
		addInitPoiTypesListener();
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		if (contextMenuLayer.getMoveableObject() instanceof OsmPoint) {
			OsmPoint movablePoint = (OsmPoint) contextMenuLayer.getMoveableObject();
			PointF pf = contextMenuLayer.getMovableCenterPoint(tileBox);
			drawPoint(canvas, movablePoint, pf.x, pf.y);
			setMovableObject(movablePoint.getLatitude(), movablePoint.getLongitude());
		}
		if (movableObject != null && !contextMenuLayer.isInChangeMarkerPositionMode()) {
			cancelMovableObject();
		}
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		super.onPrepareBufferImage(canvas, tileBox, settings);
		MapRendererView mapRenderer = getMapView().getMapRenderer();
		if (mapRenderer != null) {
			if (!poiTypesInitialized) {
				return;
			}
			if (tileBox.getZoom() < START_ZOOM) {
				clearMapMarkersCollections();
				return;
			}
			List<OsmNotesPoint> notesPoints = plugin.getDBBug().getOsmBugsPoints();
			List<OpenstreetmapPoint> osmPoints = plugin.getDBPOI().getOpenstreetmapPoints();
			int pointsSize = notesPoints.size() + osmPoints.size();
			if ((mapMarkersCollection != null && mapMarkersCollection.getMarkers().size() != pointsSize)
				|| nightMode != settings.isNightMode() || storedTextScale != getTextScale()) {
				clearMapMarkersCollections();
			}
			nightMode = settings.isNightMode();
			storedTextScale = getTextScale();
			if (pointsSize > 0 && mapMarkersCollection == null) {
				mapMarkersCollection = new MapMarkersCollection();
				List<LatLon> fullObjectsLatLon = new ArrayList<>();
				showOsmPoints(notesPoints, fullObjectsLatLon);
				showOsmPoints(osmPoints, fullObjectsLatLon);
				if (fullObjectsLatLon.size() > 0) {
					mapRenderer.addSymbolsProvider(mapMarkersCollection);
					this.fullObjectsLatLon = fullObjectsLatLon;
				}
			}
		} else {
			drawnOsmEdits.clear();
			if (tileBox.getZoom() >= START_ZOOM) {
				List<LatLon> fullObjectsLatLon = new ArrayList<>();
				drawOsmBugsPoints(canvas, tileBox, fullObjectsLatLon);
				drawOpenstreetmapPoints(canvas, tileBox, fullObjectsLatLon);
				this.fullObjectsLatLon = fullObjectsLatLon;
			}
			if (mapTextLayer != null && isTextVisible()) {
				mapTextLayer.putData(this, drawnOsmEdits);
			}
		}
	}

	private void drawOsmBugsPoints(Canvas canvas, RotatedTileBox tileBox, List<LatLon> fullObjectsLatLon) {
		drawPoints(canvas, tileBox, plugin.getDBBug().getOsmBugsPoints(), fullObjectsLatLon);
	}

	private void drawOpenstreetmapPoints(Canvas canvas, RotatedTileBox tileBox, List<LatLon> fullObjectsLatLon) {
		List<OpenstreetmapPoint> objects = plugin.getDBPOI().getOpenstreetmapPoints();
		drawnOsmEdits.addAll(drawPoints(canvas, tileBox, objects, fullObjectsLatLon));
	}

	private List<OsmPoint> drawPoints(Canvas canvas, RotatedTileBox tileBox,
									  List<? extends OsmPoint> objects, List<LatLon> fullObjectsLatLon) {
		float iconSize = getIconSize(app);
		List<OsmPoint> fullObjects = new ArrayList<>();
		for (OsmPoint o : objects) {
			if (contextMenuLayer.getMoveableObject() != o) {
				float x = tileBox.getPixXFromLatLon(o.getLatitude(), o.getLongitude());
				float y = tileBox.getPixYFromLatLon(o.getLatitude(), o.getLongitude());
				if (tileBox.containsPoint(x, y, iconSize)) {
					drawPoint(canvas, o, x, y);
					fullObjects.add(o);
					fullObjectsLatLon.add(new LatLon(o.getLatitude(), o.getLongitude()));
				}
			}
		}
		return fullObjects;
	}

	private void drawPoint(Canvas canvas, OsmPoint osmPoint, float x, float y) {
		float textScale = getTextScale();
		int iconId = getIconId(osmPoint);
		BackgroundType backgroundType = DEFAULT_BACKGROUND_TYPE;
		if (osmPoint.getGroup() == OsmPoint.Group.BUG) {
			backgroundType = BackgroundType.COMMENT;
		}
		PointImageDrawable pointImageDrawable = PointImageDrawable.getOrCreate(ctx,
				ContextCompat.getColor(ctx, R.color.created_poi_icon_color), true, false,
				iconId, backgroundType);
		pointImageDrawable.setAlpha(0.8f);
		int offsetY = backgroundType.getOffsetY(ctx, textScale);
		pointImageDrawable.drawPoint(canvas, x, y - offsetY, textScale, false);
	}

	public int getIconId(OsmPoint osmPoint) {
		if (osmPoint.getGroup() == OsmPoint.Group.POI) {
			OpenstreetmapPoint osmP = (OpenstreetmapPoint) osmPoint;
			int iconResId = 0;
			String poiTranslation = osmP.getEntity().getTag(Entity.POI_TYPE_TAG);
			if (poiTranslation != null && ctx != null) {
				Map<String, PoiType> poiTypeMap = app.getPoiTypes().getAllTranslatedNames(false);
				PoiType poiType = poiTypeMap.get(poiTranslation.toLowerCase());
				if (poiType != null) {
					String id = null;
					if (RenderingIcons.containsBigIcon(poiType.getIconKeyName())) {
						id = poiType.getIconKeyName();
					} else if (RenderingIcons.containsBigIcon(poiType.getOsmTag() + "_" + poiType.getOsmValue())) {
						id = poiType.getOsmTag() + "_" + poiType.getOsmValue();
					}
					if (id != null) {
						iconResId = RenderingIcons.getBigIconResourceId(id);
					}
				}
			}
			if (iconResId == 0) {
				iconResId = R.drawable.ic_action_info_dark;
			}
			return iconResId;
		} else if (osmPoint.getGroup() == OsmPoint.Group.BUG) {
			return R.drawable.mm_special_symbol_plus;
		} else {
			return 0;
		}
	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}


	public void getOsmEditsFromPoint(PointF point, RotatedTileBox tileBox, List<? super OsmPoint> am) {
		int ex = (int) point.x;
		int ey = (int) point.y;
		int compare = getScaledTouchRadius(app, getRadiusPoi(tileBox));
		int radius = compare * 3 / 2;
		compare = getFromPoint(tileBox, am, ex, ey, compare, radius, plugin.getDBBug().getOsmBugsPoints());
		getFromPoint(tileBox, am, ex, ey, compare, radius, plugin.getDBPOI().getOpenstreetmapPoints());
	}

	private int getFromPoint(RotatedTileBox tileBox, List<? super OsmPoint> am, int ex, int ey, int compare,
							 int radius, List<? extends OsmPoint> points) {
		for (OsmPoint n : points) {
			PointF pixel = NativeUtilities.getPixelFromLatLon(getMapRenderer(), tileBox, n.getLatitude(), n.getLongitude());
			if (calculateBelongs(ex, ey, (int) pixel.x, (int) pixel.y, compare)) {
				compare = radius;
				am.add(n);
			}
		}
		return compare;
	}

	private boolean calculateBelongs(int ex, int ey, int objx, int objy, int radius) {
		return Math.abs(objx - ex) <= radius && (ey - objy) <= radius / 2 && (objy - ey) <= 3 * radius;
	}

	public int getRadiusPoi(RotatedTileBox tb) {
		int r;
		if (tb.getZoom() < START_ZOOM) {
			r = 0;
		} else {
			r = 15;
		}
		return (int) (r * tb.getDensity());
	}

	@Override
	public boolean disableSingleTap() {
		return false;
	}

	@Override
	public boolean disableLongPressOnMap(PointF point, RotatedTileBox tileBox) {
		return false;
	}

	@Override
	public boolean isObjectClickable(Object o) {
		return o instanceof OsmPoint;
	}

	@Override
	public boolean runExclusiveAction(Object o, boolean unknownLocation) {
		return false;
	}

	@Override
	public boolean showMenuAction(@Nullable Object o) {
		return false;
	}

	@Override
	public void collectObjectsFromPoint(PointF point, RotatedTileBox tileBox, List<Object> o, boolean unknownLocation) {
		if (tileBox.getZoom() >= START_ZOOM) {
			getOsmEditsFromPoint(point, tileBox, o);
		}
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		if (o instanceof OsmPoint) {
			return new LatLon(((OsmPoint) o).getLatitude(), ((OsmPoint) o).getLongitude());
		}
		return null;
	}

	@Override
	public PointDescription getObjectName(Object o) {
		if (o instanceof OsmPoint) {
			OsmPoint point = (OsmPoint) o;
			String name = "";
			String type = "";
			if (point.getGroup() == OsmPoint.Group.POI) {
				name = ((OpenstreetmapPoint) point).getName();
				type = PointDescription.POINT_TYPE_OSM_NOTE;
			} else if (point.getGroup() == OsmPoint.Group.BUG) {
				name = ((OsmNotesPoint) point).getText();
				type = PointDescription.POINT_TYPE_OSM_BUG;
			}
			return new PointDescription(type, name);
		}
		return null;
	}

	@Override
	public boolean isObjectMovable(Object o) {
		return o instanceof OsmPoint;
	}

	@Override
	public void applyNewObjectPosition(@NonNull Object o, @NonNull LatLon position, @Nullable ApplyMovedObjectCallback callback) {
		if (o instanceof OsmPoint) {
			if (o instanceof OpenstreetmapPoint) {
				OpenstreetmapPoint objectInMotion = (OpenstreetmapPoint) o;
				Entity entity = objectInMotion.getEntity();
				entity.setLatitude(position.getLatitude());
				entity.setLongitude(position.getLongitude());
				new SaveOsmChangeAsyncTask(mOsmChangeUtil, objectInMotion, callback).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
			} else if (o instanceof OsmNotesPoint) {
				OsmNotesPoint objectInMotion = (OsmNotesPoint) o;
				objectInMotion.setLatitude(position.getLatitude());
				objectInMotion.setLongitude(position.getLongitude());
				new SaveOsmNoteAsyncTask(objectInMotion.getText(), ctx, callback, plugin, mOsmBugsUtil)
						.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, objectInMotion);
			}
			applyMovableObject(position);
		}
	}

	@Override
	public LatLon getTextLocation(OpenstreetmapPoint o) {
		return o.getLocation();
	}

	@Override
	public int getTextShift(OpenstreetmapPoint o, RotatedTileBox rb) {
		return (int) (16 * rb.getDensity() * getTextScale());
	}

	@Override
	public String getText(OpenstreetmapPoint o) {
		return o.getName();
	}

	@Override
	public boolean isTextVisible() {
		return app.getSettings().SHOW_POI_LABEL.get();
	}

	@Override
	public boolean isFakeBoldText() {
		return false;
	}

	/** OpenGL */
	private void showOsmPoints(List<? extends OsmPoint> objects, List<LatLon> fullObjectsLatLon) {
		MapRendererView mapRenderer = getMapView().getMapRenderer();
		if (mapRenderer == null) {
			return;
		}
		for (OsmPoint o : objects) {
			drawPoint(o);
			fullObjectsLatLon.add(new LatLon(o.getLatitude(), o.getLongitude()));
		}
	}

	/** OpenGL */
	private void drawPoint(@NonNull OsmPoint osmPoint) {
		if (mapMarkersCollection == null) {
			return;
		}
		float textScale = getTextScale();
		int iconId = getIconId(osmPoint);//TODO bug with detect icon
		BackgroundType backgroundType = DEFAULT_BACKGROUND_TYPE;
		if (osmPoint.getGroup() == OsmPoint.Group.BUG) {
			backgroundType = BackgroundType.COMMENT;
		}
		int x = MapUtils.get31TileNumberX(osmPoint.getLongitude());
		int y = MapUtils.get31TileNumberY(osmPoint.getLatitude());
		PointI position = new PointI(x, y);
		PointImageDrawable pointImageDrawable = PointImageDrawable.getOrCreate(ctx,
				ContextCompat.getColor(ctx, R.color.created_poi_icon_color), true, false,
				iconId, backgroundType);
		pointImageDrawable.setAlpha(0.8f);
		Bitmap bitmap  = pointImageDrawable.getBigMergedBitmap(textScale, false);
		if (bitmap == null) {
			return;
		}

		MapMarkerBuilder mapMarkerBuilder = new MapMarkerBuilder();
		mapMarkerBuilder
				.setPosition(position)
				.setIsHidden(false)
				.setBaseOrder(getPointsOrder())
				.setPinIcon(NativeUtilities.createSkImageFromBitmap(bitmap))
				.setPinIconHorisontalAlignment(MapMarker.PinIconHorisontalAlignment.CenterHorizontal);

		if (osmPoint instanceof OsmNotesPoint) {
			mapMarkerBuilder.setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.Top);
		} else {
			mapMarkerBuilder.setPinIconVerticalAlignment(MapMarker.PinIconVerticalAlignment.CenterVertical);
		}

		if (isTextVisible() && osmPoint instanceof OpenstreetmapPoint) {
			mapMarkerBuilder
					.setCaptionStyle(getTextStyle())
					.setCaptionTopSpace(0)
					.setCaption(getText((OpenstreetmapPoint) osmPoint));
		}
		mapMarkerBuilder.buildAndAddToCollection(mapMarkersCollection);
	}

	/** OpenGL */
	private TextRasterizer.Style getTextStyle() {
		return MapTextLayer.getTextStyle(getContext(), nightMode, getTextScale(), view.getDensity());
	}

	private void addInitPoiTypesListener() {
		if (app.isApplicationInitializing()) {
			app.getAppInitializer().addListener(new AppInitializer.AppInitializeListener() {

				@Override
				public void onProgress(@NonNull AppInitializer init, @NonNull InitEvents event) {
					if (event == AppInitializer.InitEvents.POI_TYPES_INITIALIZED) {
						poiTypesInitialized = true;
					}
				}
			});
		} else {
			poiTypesInitialized = true;
		}
	}
}
