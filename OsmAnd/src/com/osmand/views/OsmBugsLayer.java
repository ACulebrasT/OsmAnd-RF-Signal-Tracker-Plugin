package com.osmand.views;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.osmand.LogUtil;
import com.osmand.OsmandSettings;
import com.osmand.R;
import com.osmand.osm.MapUtils;

public class OsmBugsLayer implements OsmandMapLayer {

	private static final Log log = LogUtil.getLog(OsmBugsLayer.class); 
	private final static int startZoom = 8;
	private final int SEARCH_LIMIT = 100;
	
	private OsmandMapTileView view;
	private Handler handlerToLoop;
	private Rect pixRect = new Rect();
	private RectF tileRect = new RectF();
	
	private List<OpenStreetBug> objects = new ArrayList<OpenStreetBug>();
	private Paint pointClosedUI;
	private Paint pointOpenedUI;
	private Pattern patternToParse = Pattern.compile("putAJAXMarker\\((\\d*), ((\\d|\\.)*), ((\\d|\\.)*), '([^']*)', (\\d)\\);"); //$NON-NLS-1$
	private SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy hh:mm aaa", Locale.US); //$NON-NLS-1$
	
	private double cTopLatitude;
	private double cBottomLatitude;
	private double cLeftLongitude;
	private double cRightLongitude;
	private int czoom;
	private final Activity activity;
	
	public OsmBugsLayer(Activity activity){
		this.activity = activity;
		
	}
	
	@Override
	public void initLayer(OsmandMapTileView view) {
		this.view = view;
		synchronized (this) {
			if (handlerToLoop == null) {
				new Thread("Open street bugs layer") { //$NON-NLS-1$
					@Override
					public void run() {
						Looper.prepare();
						handlerToLoop = new Handler();
						Looper.loop();
					}
				}.start();
			}
			
		}
		pointOpenedUI = new Paint();
		pointOpenedUI.setColor(Color.RED);
		pointOpenedUI.setAlpha(200);
		pointOpenedUI.setAntiAlias(true);
		pointClosedUI = new Paint();
		pointClosedUI.setColor(Color.GREEN);
		pointClosedUI.setAlpha(200);
		pointClosedUI.setAntiAlias(true);
		pixRect.set(0, 0, view.getWidth(), view.getHeight());
	}

	@Override
	public void destroyLayer() {
		synchronized (this) {
			if(handlerToLoop != null){
				handlerToLoop.post(new Runnable(){
					@Override
					public void run() {
						Looper.myLooper().quit();
					}
				});
				handlerToLoop = null;
			}
		}
	}

	@Override
	public boolean drawInScreenPixels() {
		return false;
	}

	@Override
	public void onDraw(Canvas canvas) {
		if (view.getZoom() >= startZoom) {
			pixRect.set(0, 0, view.getWidth(), view.getHeight());
			view.calculateTileRectangle(pixRect, view.getCenterPointX(), 
					view.getCenterPointY(), view.getXTile(), view.getYTile(), tileRect);
			double topLatitude = MapUtils.getLatitudeFromTile(view.getZoom(), tileRect.top);
			double leftLongitude = MapUtils.getLongitudeFromTile(view.getZoom(), tileRect.left);
			double bottomLatitude = MapUtils.getLatitudeFromTile(view.getZoom(), tileRect.bottom);
			double rightLongitude = MapUtils.getLongitudeFromTile(view.getZoom(), tileRect.right);

			
			// request to load
			requestToLoad(topLatitude, leftLongitude, bottomLatitude, rightLongitude, view.getZoom());
			for (OpenStreetBug o : objects) {
				int x = view.getMapXForPoint(o.getLongitude());
				int y = view.getMapYForPoint(o.getLatitude());
				canvas.drawCircle(x, y, getRadiusBug(view.getZoom()), o.isOpened()? pointOpenedUI: pointClosedUI);
			}

		}
	}
	
	public int getRadiusBug(int zoom){
		if(zoom < startZoom){
			return 0;
		} else if(zoom <= 12){
			return 6;
		} else if(zoom <= 15){
			return 10;
		} else if(zoom == 16){
			return 13;
		} else if(zoom == 17){
			return 15;
		} else {
			return 18;
		}
	}
	
	public void requestToLoad(double topLatitude, double leftLongitude, double bottomLatitude,double rightLongitude, final int zoom){
		boolean inside = cTopLatitude >= topLatitude && cLeftLongitude <= leftLongitude && cRightLongitude >= rightLongitude
						&& cBottomLatitude <= bottomLatitude;
		if(!inside || (czoom != zoom && objects.size() >= SEARCH_LIMIT)){
			handlerToLoop.removeMessages(1);
			final double nTopLatitude = topLatitude + (topLatitude -bottomLatitude);
			final double nBottomLatitude = bottomLatitude - (topLatitude -bottomLatitude);
			final double nLeftLongitude = leftLongitude - (rightLongitude - leftLongitude);
			final double nRightLongitude = rightLongitude + (rightLongitude - leftLongitude);
			Message msg = Message.obtain(handlerToLoop, new Runnable() {
				@Override
				public void run() {
					if(handlerToLoop != null && !handlerToLoop.hasMessages(1)){
						boolean inside = cTopLatitude >= nTopLatitude && cLeftLongitude <= nLeftLongitude && cRightLongitude >= nRightLongitude
										&& cBottomLatitude <= nBottomLatitude;
						if (!inside || czoom != zoom) {
							objects = loadingBugs(nTopLatitude, nLeftLongitude, nBottomLatitude, nRightLongitude);
							cTopLatitude = nTopLatitude;
							cLeftLongitude = nLeftLongitude;
							cRightLongitude = nRightLongitude;
							cBottomLatitude = nBottomLatitude;
							czoom = zoom;
							view.refreshMap();
						}
					}
				}
			});
			msg.what = 1;
			handlerToLoop.sendMessage(msg);
		}
	}
	
	@Override
	public boolean onLongPressEvent(PointF point) {
		final OpenStreetBug bug = getBugFromPoint(point);
		if(bug != null){
			Builder builder = new AlertDialog.Builder(view.getContext());
			Resources resources = view.getContext().getResources();
	    	builder.setItems(new String[]{
	    			resources.getString(R.string.osb_comment_menu_item),
	    			resources.getString(R.string.osb_close_menu_item)
	    		}, new DialogInterface.OnClickListener(){
				@Override
				public void onClick(DialogInterface dialog, int which) {
					if(which == 0){
						commentBug(view.getContext(), activity.getLayoutInflater(), bug);
					} else if(which == 1){
						closeBug(view.getContext(), activity.getLayoutInflater(), bug);
					}
				}
	    	});
			builder.create().show();
			return true;
		}
		return false;
	}
	
	public OpenStreetBug getBugFromPoint(PointF point){
		OpenStreetBug result = null;
		if (objects != null) {
			int ex = (int) point.x;
			int ey = (int) point.y;
			int radius = getRadiusBug(view.getZoom()) * 3 / 2;
			try {
				for (int i = 0; i < objects.size(); i++) {
					OpenStreetBug n = objects.get(i);
					int x = view.getRotatedMapXForPoint(n.getLatitude(), n.getLongitude());
					int y = view.getRotatedMapYForPoint(n.getLatitude(), n.getLongitude());
					if (Math.abs(x - ex) <= radius && Math.abs(y - ey) <= radius) {
						radius = Math.max(Math.abs(x - ex), Math.abs(y - ey));
						result = n;
					}
				}
			} catch (IndexOutOfBoundsException e) {
				// that's really rare case, but is much efficient than introduce synchronized block
			}
		}
		return result;
	}

	@Override
	public boolean onTouchEvent(PointF point) {
		OpenStreetBug bug = getBugFromPoint(point);
		if(bug != null){
			String format = "Bug : " + bug.getName(); //$NON-NLS-1$
			Toast.makeText(view.getContext(), format, Toast.LENGTH_LONG).show();
			return true;
		}
		return false;
	}
	
	


	public void clearCache() {
		objects.clear();
		cTopLatitude = 0;
		cBottomLatitude = 0;
		cLeftLongitude = 0;
		cRightLongitude = 0;
	}
	
	public boolean createNewBug(double latitude, double longitude, String text, String authorName){
		StringBuilder b = new StringBuilder();
		b.append("http://openstreetbugs.schokokeks.org/api/0.1/addPOIexec?"); //$NON-NLS-1$
		b.append("lat=").append(latitude); //$NON-NLS-1$
		b.append("&lon=").append(longitude); //$NON-NLS-1$
		text = text + " [" + authorName +" "+ dateFormat.format(new Date())+ "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		b.append("&text=").append(URLEncoder.encode(text)); //$NON-NLS-1$
		b.append("&name=").append(URLEncoder.encode(authorName)); //$NON-NLS-1$
		return editingPOI(b.toString(), "creating bug"); //$NON-NLS-1$
	}
	
	public boolean addingComment(long id, String text, String authorName){
		StringBuilder b = new StringBuilder();
		b.append("http://openstreetbugs.schokokeks.org/api/0.1/editPOIexec?"); //$NON-NLS-1$
		b.append("id=").append(id); //$NON-NLS-1$
		text = text + " [" + authorName +" "+ dateFormat.format(new Date())+ "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		b.append("&text=").append(URLEncoder.encode(text)); //$NON-NLS-1$
		b.append("&name=").append(URLEncoder.encode(authorName)); //$NON-NLS-1$
		return editingPOI(b.toString(), "adding comment"); //$NON-NLS-1$
	}
	
	public boolean closingBug(long id){
		StringBuilder b = new StringBuilder();
		b.append("http://openstreetbugs.schokokeks.org/api/0.1/closePOIexec?"); //$NON-NLS-1$
		b.append("id=").append(id); //$NON-NLS-1$
		return editingPOI(b.toString(),"closing bug"); //$NON-NLS-1$
	}
	
	
	private boolean editingPOI(String urlStr, String debugAction){
		try {
			log.debug("Action " + debugAction + " " + urlStr); //$NON-NLS-1$ //$NON-NLS-2$
			URL url = new URL(urlStr);
			URLConnection connection = url.openConnection();
			BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			while(reader.readLine() != null){
			}
			log.debug("Action " + debugAction + " successfull"); //$NON-NLS-1$ //$NON-NLS-2$
			return true;
		} catch (IOException e) {
			log.error("Error " +debugAction, e); //$NON-NLS-1$
		} catch (RuntimeException e) {
			log.error("Error "+debugAction, e); //$NON-NLS-1$
		} 
		return false;
	}
	
	protected List<OpenStreetBug> loadingBugs(double topLatitude, double leftLongitude, double bottomLatitude,double rightLongitude){
		List<OpenStreetBug> bugs = new ArrayList<OpenStreetBug>();
		StringBuilder b = new StringBuilder();
		b.append("http://openstreetbugs.schokokeks.org/api/0.1/getBugs?"); //$NON-NLS-1$
		b.append("b=").append(bottomLatitude); //$NON-NLS-1$
		b.append("&t=").append(topLatitude); //$NON-NLS-1$
		b.append("&l=").append(leftLongitude); //$NON-NLS-1$
		b.append("&r=").append(rightLongitude); //$NON-NLS-1$
		try {
			URL url = new URL(b.toString());
			URLConnection connection = url.openConnection();
			BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			String st = null;
			while((st = reader.readLine()) != null){
				Matcher matcher = patternToParse.matcher(st);
				if(matcher.find()){
					OpenStreetBug bug = new OpenStreetBug();
					bug.setId(Long.parseLong(matcher.group(1)));
					bug.setLongitude(Double.parseDouble(matcher.group(2)));
					bug.setLatitude(Double.parseDouble(matcher.group(4)));
					bug.setName(matcher.group(6).replace("<hr />", "\n")); //$NON-NLS-1$ //$NON-NLS-2$
					bug.setOpened(matcher.group(7).equals("0")); //$NON-NLS-1$
					bugs.add(bug);
				}
			}
		} catch (IOException e) {
			log.warn("Error loading bugs", e); //$NON-NLS-1$
		} catch (NumberFormatException e) {
			log.warn("Error loading bugs", e); //$NON-NLS-1$
		} catch (RuntimeException e) {
			log.warn("Error loading bugs", e); //$NON-NLS-1$
		} 
		
		return bugs;
	}
	

	public void openBug(final Context ctx, LayoutInflater layoutInflater, final OsmandMapTileView mapView,  final double latitude, final double longitude){
		Builder builder = new AlertDialog.Builder(ctx);
		builder.setTitle(R.string.osb_add_dialog_title);
		final View view = layoutInflater.inflate(R.layout.open_bug, null);
		builder.setView(view);
		((EditText)view.findViewById(R.id.AuthorName)).setText(OsmandSettings.getUserName(ctx));
		builder.setNegativeButton(R.string.default_buttons_cancel, null);
		builder.setPositiveButton(R.string.default_buttons_add, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				String text = ((EditText)view.findViewById(R.id.BugMessage)).getText().toString();
				String author = ((EditText)view.findViewById(R.id.AuthorName)).getText().toString();
				// do not set name as author it is ridiculous in that case
//				OsmandSettings.setUserName(ctx, author);
				boolean bug = createNewBug(latitude, longitude, 
						text, author);
		    	if (bug) {
		    		Toast.makeText(ctx, ctx.getResources().getString(R.string.osb_add_dialog_success), Toast.LENGTH_LONG).show();
					clearCache();
					if (mapView.getLayers().contains(OsmBugsLayer.this)) {
						mapView.refreshMap();
					}
				} else {
					Toast.makeText(ctx, ctx.getResources().getString(R.string.osb_add_dialog_error), Toast.LENGTH_LONG).show();
				}
			}
		});
		builder.show();
	}
	
	public void commentBug(final Context ctx, LayoutInflater layoutInflater, final OpenStreetBug bug){
		Builder builder = new AlertDialog.Builder(ctx);
		builder.setTitle(R.string.osb_comment_dialog_title);
		final View view = layoutInflater.inflate(R.layout.open_bug, null);
		builder.setView(view);
		((EditText)view.findViewById(R.id.AuthorName)).setText(OsmandSettings.getUserName(ctx));
		builder.setNegativeButton(R.string.default_buttons_cancel, null);
		builder.setPositiveButton(R.string.osb_comment_dialog_add_button, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				String text = ((EditText)view.findViewById(R.id.BugMessage)).getText().toString();
				String author = ((EditText)view.findViewById(R.id.AuthorName)).getText().toString();
//				OsmandSettings.setUserName(ctx, author);
				boolean added = addingComment(bug.getId(), text, author);
		    	if (added) {
		    		Toast.makeText(ctx, ctx.getResources().getString(R.string.osb_comment_dialog_success), Toast.LENGTH_LONG).show();
					clearCache();
					if (OsmBugsLayer.this.view.getLayers().contains(OsmBugsLayer.this)) {
						OsmBugsLayer.this.view.refreshMap();
					}
				} else {
					Toast.makeText(ctx, ctx.getResources().getString(R.string.osb_comment_dialog_error), Toast.LENGTH_LONG).show();
				}
			}
		});
		builder.show();
	}
	
	public void closeBug(final Context ctx, LayoutInflater layoutInflater, final OpenStreetBug bug){
		Builder builder = new AlertDialog.Builder(ctx);
		builder.setTitle(R.string.osb_close_dialog_title);
		builder.setNegativeButton(R.string.default_buttons_cancel, null);
		builder.setPositiveButton(R.string.osb_close_dialog_close_button, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				boolean closed = closingBug(bug.getId());
		    	if (closed) {
		    		Toast.makeText(ctx, ctx.getResources().getString(R.string.osb_close_dialog_success), Toast.LENGTH_LONG).show();
					clearCache();
					if (OsmBugsLayer.this.view.getLayers().contains(OsmBugsLayer.this)) {
						OsmBugsLayer.this.view.refreshMap();
					}
				} else {
					Toast.makeText(ctx, ctx.getResources().getString(R.string.osb_close_dialog_error), Toast.LENGTH_LONG).show();
				}
			}
		});
		builder.show();
	}


	
	public static class OpenStreetBug {
		private double latitude;
		private double longitude;
		private String name;
		private long id;
		private boolean opened;
		public double getLatitude() {
			return latitude;
		}
		public void setLatitude(double latitude) {
			this.latitude = latitude;
		}
		public double getLongitude() {
			return longitude;
		}
		public void setLongitude(double longitude) {
			this.longitude = longitude;
		}
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public long getId() {
			return id;
		}
		public void setId(long id) {
			this.id = id;
		}
		public boolean isOpened() {
			return opened;
		}
		public void setOpened(boolean opened) {
			this.opened = opened;
		}
		
		
	}

}
