package net.osmand.plus.myplaces;

import static net.osmand.data.FavouritePoint.DEFAULT_BACKGROUND_TYPE;

import android.content.Context;

import androidx.annotation.NonNull;

import net.osmand.GPXUtilities.PointsGroup;
import net.osmand.data.BackgroundType;
import net.osmand.data.FavouritePoint;
import net.osmand.plus.R;

import java.util.ArrayList;
import java.util.List;

public class FavoriteGroup {

	public static final String PERSONAL_CATEGORY = "personal";

	private String name;
	private String iconName;
	private BackgroundType backgroundType;
	private List<FavouritePoint> points = new ArrayList<>();

	private int color;
	private boolean visible = true;

	public FavoriteGroup() {
	}

	public FavoriteGroup(@NonNull FavouritePoint point) {
		name = point.getCategory();
		color = point.getColor();
		visible = point.isVisible();
		iconName = point.getIconName();
		backgroundType = point.getBackgroundType();
	}

	public FavoriteGroup(String name, List<FavouritePoint> points, int color, boolean visible) {
		this.name = name;
		this.color = color;
		this.points = points;
		this.visible = visible;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getColor() {
		return color;
	}

	public void setColor(int color) {
		this.color = color;
	}

	public boolean isVisible() {
		return visible;
	}

	public void setVisible(boolean visible) {
		this.visible = visible;
	}

	public String getIconName() {
		return iconName;
	}

	public void setIconName(String iconName) {
		this.iconName = iconName;
	}

	public BackgroundType getBackgroundType() {
		return backgroundType == null ? DEFAULT_BACKGROUND_TYPE : backgroundType;
	}

	public void setBackgroundType(BackgroundType backgroundType) {
		this.backgroundType = backgroundType;
	}

	public List<FavouritePoint> getPoints() {
		return points;
	}

	public boolean isPersonal() {
		return isPersonal(name);
	}

	public String getDisplayName(@NonNull Context ctx) {
		return getDisplayName(ctx, name);
	}

	public static String getDisplayName(@NonNull Context ctx, String name) {
		if (isPersonal(name)) {
			return ctx.getString(R.string.personal_category_name);
		} else if (name.isEmpty()) {
			return ctx.getString(R.string.shared_string_favorites);
		} else {
			return name;
		}
	}

	private static boolean isPersonal(@NonNull String name) {
		return PERSONAL_CATEGORY.equals(name);
	}

	public static boolean isPersonalCategoryDisplayName(@NonNull Context ctx, @NonNull String name) {
		return name.equals(ctx.getString(R.string.personal_category_name));
	}

	public static String convertDisplayNameToGroupIdName(@NonNull Context context, @NonNull String name) {
		if (isPersonalCategoryDisplayName(context, name)) {
			return PERSONAL_CATEGORY;
		}
		if (name.equals(context.getString(R.string.shared_string_favorites))) {
			return "";
		}
		return name;
	}

	public PointsGroup toPointsGroup() {
		return new PointsGroup(getName(), getIconName(), getBackgroundType().getTypeName(), getColor(), points.size());
	}
}
