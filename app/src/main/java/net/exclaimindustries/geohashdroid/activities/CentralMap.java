/**
 * CentralMap.java
 * Copyright (C)2015 Nicholas Killewald
 * 
 * This file is distributed under the terms of the BSD license.
 * The source package should have a LICENSE file at the toplevel.
 */
package net.exclaimindustries.geohashdroid.activities;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.commonsware.cwac.wakeful.WakefulIntentService;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

import net.exclaimindustries.geohashdroid.R;
import net.exclaimindustries.geohashdroid.UnitConverter;
import net.exclaimindustries.geohashdroid.fragments.GraticulePickerFragment;
import net.exclaimindustries.geohashdroid.fragments.NearbyGraticuleDialogFragment;
import net.exclaimindustries.geohashdroid.services.StockService;
import net.exclaimindustries.geohashdroid.util.GHDConstants;
import net.exclaimindustries.geohashdroid.util.Graticule;
import net.exclaimindustries.geohashdroid.util.Info;
import net.exclaimindustries.geohashdroid.widgets.ErrorBanner;
import net.exclaimindustries.tools.LocationUtil;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * CentralMap replaces MainMap as the map display.  Unlike MainMap, it also
 * serves as the entry point for the entire app.  These comments are going to
 * make so much sense later when MainMap is little more than a class that only
 * exists on the legacy branch.
 */
public class CentralMap
        extends Activity
        implements GoogleApiClient.ConnectionCallbacks,
                   GoogleApiClient.OnConnectionFailedListener,
                   GoogleMap.OnInfoWindowClickListener,
                   GoogleMap.OnCameraChangeListener,
                   GoogleMap.OnMapClickListener,
                   NearbyGraticuleDialogFragment.NearbyGraticuleClickedCallback,
                   GraticulePickerFragment.GraticulePickerListener {
    private static final String DEBUG_TAG = "CentralMap";

    private static final String NEARBY_DIALOG = "nearbyDialog";
    public static final String GRATICULE_PICKER_STACK = "GraticulePickerStack";

    // If we're in Select-A-Graticule mode (as opposed to expedition mode).
    private boolean mSelectAGraticule = false;
    // If we're still waiting on initial zoom between pauses.
    private boolean mWaitingOnInitialZoom = false;
    // If we already did the initial zoom for this expedition.
    private boolean mAlreadyDidInitialZoom = false;
    // If the map's ready.
    private boolean mMapIsReady = false;

    private Info mCurrentInfo;
    private Marker mDestination;
    private Polygon mGraticuleOutline;
    private GoogleMap mMap;
    private GoogleApiClient mGoogleClient;

    private DisplayMetrics mMetrics;

    // This is either the current expedition Graticule (same as in mCurrentInfo)
    // or the last-selected Graticule in Select-A-Graticule mode (needed if we
    // need to reconstruct from an onDestroy()).
    private Graticule mLastGraticule;
    private Calendar mLastCalendar;

    // Because a null Graticule is considered to be the Globalhash indicator, we
    // need a boolean to keep track of whether we're actually in a Globalhash or
    // if we just don't have a Graticule yet.
    private boolean mGlobalhash;

    // This will hold all the nearby points we come up with.  They'll be
    // removed any time we get a new Info in.  It's a map so that we have a
    // quick way to switch to a new Info without having to call StockService.
    private final Map<Marker, Info> mNearbyPoints = new HashMap<>();

    private ErrorBanner mBanner;

    /**
     * A <code>CentralMapMode</code> is a set of behaviors that happen whenever
     * some corresponding event occurs in {@link CentralMap}.
     */
    public abstract static class CentralMapMode {
        /** Bundle key for the current Graticule. */
        public final static String GRATICULE = "graticule";
        /** Bundle key for the current date, as a Calendar. */
        public final static String CALENDAR = "calendar";
        /**
         * Bundle key for a boolean indicating that, if the Graticule is null,
         * this was actually a Globalhash, not just a request with an empty
         * Graticule.
         */
        public final static String GLOBALHASH = "globalhash";
        /**
         * Bundle key for the current Info.  In cases where this can be given,
         * the Graticule, Calendar, and boolean indicating a Globalhash can be
         * implied from it.
         */
        public final static String INFO = "info";

        /** The current GoogleMap object. */
        protected GoogleMap mMap;
        /** The calling CentralMap Activity. */
        protected CentralMap mCentralMap;

        /** The current destination Marker. */
        protected Marker mDestination;

        /**
         * Sets the {@link GoogleMap} this mode deals with.  When implementing
         * this, make sure to actually do something with it like subscribe to
         * events as the mode needs them if you're not doing so in
         * {@link #init(Bundle)}.
         *
         * @param map that map
         */
        public void setMap(GoogleMap map) {
            mMap = map;
        }

        /**
         * Sets the {@link CentralMap} to which this will talk back.
         *
         * @param centralMap that CentralMap
         */
        public void setCentralMap(CentralMap centralMap) {
            mCentralMap = centralMap;
        }

        /**
         * Gets the current GoogleApiClient held by CentralMap.  This will
         * return null if the client isn't usable (not connected, null itself,
         * etc).
         *
         * @return the current GoogleApiClient
         */
        @Nullable
        protected final GoogleApiClient getGoogleClient() {
            if(mCentralMap != null) {
                GoogleApiClient gClient = mCentralMap.getGoogleClient();
                if(gClient != null && gClient.isConnected())
                    return gClient;
                else
                    return null;
            } else {
                return null;
            }
        }

        /**
         * <p>
         * Does whatever init tomfoolery is needed for this class, using the
         * given Bundle of stuff.  You're probably best calling this AFTER
         * {@link #setMap(GoogleMap)} and {@link #setCentralMap(CentralMap)} are
         * called.
         * </p>
         *
         * @param bundle a bunch of stuff, or null if there's no stuff to be had
         */
        public abstract void init(@Nullable Bundle bundle);

        /**
         * Does whatever cleanup rigmarole is needed for this class, such as
         * unsubscribing to all those subscriptions you set up in {@link #setMap(GoogleMap)}
         * or {@link #init(Bundle)}.  If a Bundle is given, state data should be
         * written to it.  It might be used to recreate the mode, but it can
         * also be used to write out result data in the case of
         * Select-A-Graticule mode.
         *
         * @param bundle a Bundle in which state data can be written (may be null)
         */
        public abstract void cleanUp(@Nullable Bundle bundle);

        /**
         * Called when a new Info has come in from StockService.
         *
         * @param info that Info
         * @param flags the request flags that were sent with it
         */
        public abstract void handleInfo(Info info, int flags);

        /**
         * Draws a final destination point on the map given the appropriate
         * Info.  This also removes any old point that might've been around.
         *
         * @param info the new Info
         */
        protected void addDestinationPoint(Info info) {
            // Clear any old destination marker first.
            removeDestinationPoint();

            if(info == null) return;

            // We need a marker!  And that marker needs a title.  And that title
            // depends on globalhashiness and retroness.
            String title;

            if(!info.isRetroHash()) {
                // Non-retro hashes don't have today's date on them.  They just
                // have "today's [something]".
                if(info.isGlobalHash()) {
                    title = mCentralMap.getString(R.string.marker_title_today_globalpoint);
                } else {
                    title = mCentralMap.getString(R.string.marker_title_today_hashpoint);
                }
            } else {
                // Retro hashes, however, need a date string.
                String date = DateFormat.getDateInstance(DateFormat.LONG).format(info.getDate());

                if(info.isGlobalHash()) {
                    title = mCentralMap.getString(R.string.marker_title_retro_globalpoint, date);
                } else {
                    title = mCentralMap.getString(R.string.marker_title_retro_hashpoint, date);
                }
            }

            // The snippet's just the coordinates in question.  Further details
            // will go in the infobox.
            String snippet = UnitConverter.makeFullCoordinateString(mCentralMap, info.getFinalLocation(), false, UnitConverter.OUTPUT_LONG);

            // Under the current marker image, the anchor is the very bottom,
            // halfway across.  Presumably, that's what the default icon also
            // uses, but we're not concerned with the default icon, now, are we?
            mDestination = mMap.addMarker(new MarkerOptions()
                    .position(info.getFinalDestinationLatLng())
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.final_destination))
                    .anchor(0.5f, 1.0f)
                    .title(title)
                    .snippet(snippet));
        }

        /**
         * Removes the destination point, if one exists.
         */
        protected void removeDestinationPoint() {
            if(mDestination != null) {
                mDestination.remove();
                mDestination = null;
            }
        }
    }

    private class StockReceiver extends BroadcastReceiver {
        private long mWaitingOnThisOne = -1;

        public void setWaitingId(long id) {
            mWaitingOnThisOne = id;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // A stock result arrives!  Let's get data!  That oughta tell us
            // whether or not we're even going to bother with it.
            int reqFlags = intent.getIntExtra(StockService.EXTRA_REQUEST_FLAGS, 0);
            long reqId = intent.getLongExtra(StockService.EXTRA_REQUEST_ID, -1);

            // Now, if the flags state this was from the alarm or somewhere else
            // we weren't expecting, give up now.  We don't want it.
            if((reqFlags & StockService.FLAG_ALARM) != 0) return;

            // Only check the ID if this was user-initiated.  If the user didn't
            // initiate it, we might be getting responses back in bunches,
            // meaning that ID checking will be useless.
            if((reqFlags & StockService.FLAG_USER_INITIATED) != 0 && reqId != mWaitingOnThisOne) return;

            // Well, it's what we're looking for.  What was the result?  The
            // default is RESPONSE_NETWORK_ERROR, as not getting a response code
            // is a Bad Thing(tm).
            int responseCode = intent.getIntExtra(StockService.EXTRA_RESPONSE_CODE, StockService.RESPONSE_NETWORK_ERROR);

            if(responseCode == StockService.RESPONSE_OKAY) {
                // Hey, would you look at that, it actually worked!  So, get
                // the Info out of it and fire it away to the corresponding
                // method.
                Info received = intent.getParcelableExtra(StockService.EXTRA_INFO);

                if((reqFlags & StockService.FLAG_NEARBY_POINT) != 0)
                    addNearbyPoint(received);
                else {
                    boolean selectAGraticule = (reqFlags & StockService.FLAG_SELECT_A_GRATICULE) != 0;
                    mAlreadyDidInitialZoom = false;
                    setInfo(received);

                    if(!selectAGraticule) drawNearbyPoints();
                }
            } else if((reqFlags & StockService.FLAG_USER_INITIATED) != 0) {
                // ONLY notify the user of an error if they specifically
                // requested this stock.
                switch(responseCode) {
                    case StockService.RESPONSE_NOT_POSTED_YET:
                        mBanner.setText(getString(R.string.error_not_yet_posted));
                        mBanner.setErrorStatus(ErrorBanner.Status.ERROR);
                        mBanner.animateBanner(true);
                        break;
                    case StockService.RESPONSE_NO_CONNECTION:
                        mBanner.setText(getString(R.string.error_no_connection));
                        mBanner.setErrorStatus(ErrorBanner.Status.ERROR);
                        mBanner.animateBanner(true);
                        break;
                    case StockService.RESPONSE_NETWORK_ERROR:
                        mBanner.setText(getString(R.string.error_server_failure));
                        mBanner.setErrorStatus(ErrorBanner.Status.ERROR);
                        mBanner.animateBanner(true);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private StockReceiver mStockReceiver;

    private LocationListener mInitialZoomListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Got it!
            mWaitingOnInitialZoom = false;
            mAlreadyDidInitialZoom = true;
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleClient, this);
            mBanner.animateBanner(false);
            zoomToIdeal(location);
        }
    };

    private LocationListener mFindClosestListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Okay, NOW we have a location.
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleClient, this);
            mBanner.animateBanner(false);
            applyFoundGraticule(location);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load up!
        if(savedInstanceState != null) {
            mCurrentInfo = savedInstanceState.getParcelable("info");
            mAlreadyDidInitialZoom = savedInstanceState.getBoolean("alreadyZoomed", false);
            mSelectAGraticule = savedInstanceState.getBoolean("selectAGraticule", false);
            mGlobalhash = savedInstanceState.getBoolean("globalhash", false);

            mLastGraticule = savedInstanceState.getParcelable("lastGraticule");

            mLastCalendar = (Calendar)savedInstanceState.getSerializable("lastCalendar");
        }

        setContentView(R.layout.centralmap);

        // Get our metrics set up ONCE so we're not doing this setup every time
        // a camera update comes in.
        mMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(mMetrics);

        // We deal with locations, so we deal with the GoogleApiClient.  It'll
        // connect during onStart.
        mGoogleClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        mBanner = (ErrorBanner)findViewById(R.id.error_banner);
        mStockReceiver = new StockReceiver();

        // Get a map ready.  We'll know when we've got it.  Oh, we'll know.
        MapFragment mapFrag = (MapFragment)getFragmentManager().findFragmentById(R.id.map);
        mapFrag.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap googleMap) {
                mMap = googleMap;

                // I could swear you could do this in XML...
                UiSettings set = mMap.getUiSettings();

                // The My Location button has to go off, as we're going to have the
                // infobox right around there.
                set.setMyLocationButtonEnabled(false);

                mMap.setMyLocationEnabled(true);
                mMap.setOnInfoWindowClickListener(CentralMap.this);
                mMap.setOnCameraChangeListener(CentralMap.this);

                // Now, set the flag that tells everything else we're ready.
                // We'll need this because we're calling the very methods that
                // depend on it, as noted in the next comment.
                mMapIsReady = true;

                // The entire point of this async callback is that we don't have
                // any clue when it COULD come back.  This means, in theory,
                // that it MIGHT come back after the user asks for a stock or
                // whatnot, meaning an Info is waiting to be acted upon.  In
                // fact, the user might've also asked for Select-A-Graticule
                // mode.
                if(mSelectAGraticule) {
                    enterSelectAGraticuleMode(false);
                } else {
                    setInfo(mCurrentInfo);
                    drawNearbyPoints();
                }
            }
        });
    }

    @Override
    protected void onPause() {
        // The receiver goes right off as soon as we pause.
        unregisterReceiver(mStockReceiver);

        // Also, stop listening if we still haven't found what we're looking
        // for.
        if(mGoogleClient != null && mGoogleClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleClient, mInitialZoomListener);
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleClient, mFindClosestListener);
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The receiver goes on during onResume, even though we might not be
        // waiting for anything yet.
        IntentFilter filt = new IntentFilter();
        filt.addAction(StockService.ACTION_STOCK_RESULT);

        // On resume, if we were waiting on a location update before, do it
        // again.  This'll only kick in if onStop wasn't called, since onStart
        // fires off a setInfo, which in turn does the location update.
        if(mWaitingOnInitialZoom)
            doInitialZoom();

        registerReceiver(mStockReceiver, filt);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Service up!
        mGoogleClient.connect();
    }

    @Override
    protected void onStop() {
        // Service down!
        mGoogleClient.disconnect();

        // Also, don't bother with the initial zoom.  onStart will do setInfo,
        // which will in turn do that.
        mWaitingOnInitialZoom = false;

        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        // Also, keep the latest Info around.
        // TODO: Later, we'll need to know NOT to reload the Info at startup
        // time.  Determine the correct way to determine that.
        outState.putParcelable("info", mCurrentInfo);

        // Keep the various flags, too.
        outState.putBoolean("alreadyZoomed", mAlreadyDidInitialZoom);
        outState.putBoolean("selectAGraticule", mSelectAGraticule);
        outState.putBoolean("globalhash", mGlobalhash);

        // And some additional data.
        outState.putParcelable("lastGraticule", mLastGraticule);
        outState.putSerializable("lastCalendar", mLastCalendar);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();

        if(mSelectAGraticule)
            inflater.inflate(R.menu.centralmap_selectagraticule, menu);
        else
            inflater.inflate(R.menu.centralmap_expedition, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.action_selectagraticule: {
                // It's Select-A-Graticule Mode!  At long last!
                enterSelectAGraticuleMode(true);
                return true;
            }
            case R.id.action_exitgraticule: {
                // We've left Select-A-Graticule for whatever reason.
                exitSelectAGraticuleMode();
                return true;
            }
            case R.id.action_whatisthis: {
                // The everfamous and much-beloved "What's Geohashing?" button,
                // because honestly, this IS sort of confusing if you're
                // expecting something for geocaching.
                Intent i = new Intent();
                i.setAction(Intent.ACTION_VIEW);
                i.setData(Uri.parse("http://wiki.xkcd.com/geohashing/How_it_works"));
                startActivity(i);
                return true;
            }
            case R.id.action_preferences: {
                // Preferences!  To the Preferencemobile!
                Intent i = new Intent(this, PreferencesActivity.class);
                startActivity(i);
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void removeDestinationPoint() {
        if(mDestination != null) {
            mDestination.remove();

            // Since the API says the behavior of ALL methods on Marker are
            // not defined after remove() is called, we're going to null out
            // the marker immediately so we don't try to call anything on it
            // again, even if it's just remove() again.
            mDestination = null;
        }
    }

    private void removeNearbyPoints() {
        synchronized(mNearbyPoints) {
            for(Marker m : mNearbyPoints.keySet()) {
                m.remove();
            }
            mNearbyPoints.clear();
        }
    }

    private void setInfo(final Info info) {
        mCurrentInfo = info;

        // If we're not ready for the map yet, give up.  When we DO get ready,
        // we'll be called again.
        if(!mMapIsReady) return;

        // In any case, a new Info means the old one's invalid, so the old
        // Marker goes away.
        removeDestinationPoint();

        // I suppose a null Info MIGHT come in.  I don't know how yet, but sure,
        // let's assume a null Info here means we just don't render anything.
        if(mCurrentInfo != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Marker!
                    addDestinationPoint(info);

                    // Stash current data for later.
                    mLastGraticule = info.getGraticule();
                    mLastCalendar = info.getCalendar();

                    // With an Info in hand, we can also change the title.
                    StringBuilder newTitle = new StringBuilder();
                    if(info.isGlobalHash()) newTitle.append(getString(R.string.title_part_globalhash));
                    else newTitle.append(info.getGraticule().getLatitudeString(false)).append(' ').append(info.getGraticule().getLongitudeString(false));
                    newTitle.append(", ");
                    newTitle.append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(info.getDate()));
                    setTitle(newTitle.toString());

                    // Now, the Mercator projection that the map uses clips at
                    // around 85 degrees north and south.  If that's where the
                    // point is (if that's the Globalhash or if the user
                    // legitimately lives in Antarctica), we'll still try to
                    // draw it, but we'll throw up a warning that the marker
                    // might not show up.  Sure is a good thing an extreme south
                    // Globalhash showed up when I was testing this, else I
                    // honestly might've forgot.
                    if(Math.abs(info.getLatitude()) > 85) {
                        mBanner.setErrorStatus(ErrorBanner.Status.WARNING);
                        mBanner.setText(getString(R.string.warning_outside_of_projection));
                        mBanner.animateBanner(true);
                    }

                    // Finally, try to zoom the map to where it needs to be,
                    // assuming we're connected to the APIs and have a location.
                    // Note that when the APIs connect, this'll be called, so we
                    // don't need to set up a callback or whatnot.
                    if(mGoogleClient != null && mGoogleClient.isConnected()) {
//                        doInitialZoom();
                    }
                }
            });

        } else {
            // Otherwise, make sure the title's back to normal.
            setTitle(R.string.app_name);
        }
    }

    private void drawNearbyPoints() {
        if(mCurrentInfo == null) return;

        removeNearbyPoints();

        // If the user wants the nearby points (AND this isn't a Globalhash), we
        // need to request them.  Now, the way we're going to do this may seem
        // inefficient, firing off (up to) eight more Intents to StockService,
        // but it covers the bizarre cases of people trying to Geohash directly
        // on the 30W or 180E/W lines, as well as any oddities related to the
        // zero graticules.  Besides, it's best to keep StockService simple.
        // The cache will ensure the points will come back promptly in the
        // general case.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(CentralMap.this);
        if(!mCurrentInfo.isGlobalHash() && prefs.getBoolean(GHDConstants.PREF_NEARBY_POINTS, true)) {
            Graticule g = mCurrentInfo.getGraticule();

            for(int i = -1; i <= 1; i++) {
                for(int j = -1; j <= 1; j++) {
                    // Zero and zero isn't a nearby point, that's the very point
                    // we're at right now!
                    if(i == 0 && j == 0) continue;

                    // If the user's truly adventurous enough to go to the 90N/S
                    // graticules, there aren't any nearby points north/south of
                    // where they are.  Also, the nearby points aren't going to
                    // be drawn anyway due to the projection, but hey, that's
                    // nitpicking.
                    if(Math.abs((g.isSouth() ? -1 : 1) * g.getLatitude() + i) > 90)
                        continue;

                    // Make a new Graticule, properly offset...
                    Graticule offset = Graticule.createOffsetFrom(g, i, j);

                    // ...and make the request, WITH the appropriate flag set.
                    requestStock(offset, mCurrentInfo.getCalendar(), StockService.FLAG_AUTO_INITIATED | StockService.FLAG_NEARBY_POINT);
                }
            }
        }
    }

    private void addNearbyPoint(Info info) {
        // This will get called repeatedly up to eight times (in rare cases,
        // five times) when we ask for nearby points.  All we need to do is put
        // those points on the map, and stuff them in the map.  Two different
        // varieties of map.
        synchronized(mNearbyPoints) {
            // The title might be a wee bit unwieldy, as it also has to include
            // the graticule's location.  We DO know that this isn't a
            // Globalhash, though.
            String title;
            String gratString = info.getGraticule().getLatitudeString(false) + " " + info.getGraticule().getLongitudeString(false);
            if(info.isRetroHash()) {
                title = getString(R.string.marker_title_nearby_retro_hashpoint,
                        DateFormat.getDateInstance(DateFormat.LONG).format(info.getDate()),
                        gratString);
            } else {
                title = getString(R.string.marker_title_nearby_today_hashpoint,
                        gratString);
            }

            // Snippet!  Snippet good.
            String snippet = UnitConverter.makeFullCoordinateString(CentralMap.this, info.getFinalLocation(), false, UnitConverter.OUTPUT_LONG);

            Marker nearby = mMap.addMarker(new MarkerOptions()
                    .position(info.getFinalDestinationLatLng())
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.final_destination_disabled))
                    .anchor(0.5f, 1.0f)
                    .title(title)
                    .snippet(snippet));

            mNearbyPoints.put(nearby, info);

            // Finally, make sure it should be visible.  Do this per-marker, as
            // we're not always sure we've got the full set of eight (edge case
            // involving the poles) or if all of them will come in at the same
            // time (edge cases involving 30W or 180E/W).
            checkMarkerVisibility(nearby);
        }
    }

    private void addDestinationPoint(Info info) {
        // Clear any old destination marker first.
        removeDestinationPoint();

        // We need a marker!  And that marker needs a title.  And
        // that title depends on globalhashiness and retroness.
        String title;

        if(!info.isRetroHash()) {
            // Non-retro hashes don't have today's date on them.
            // They just have "today's [something]".
            if(info.isGlobalHash()) {
                title = getString(R.string.marker_title_today_globalpoint);
            } else {
                title = getString(R.string.marker_title_today_hashpoint);
            }
        } else {
            // Retro hashes, however, need a date string.
            String date = DateFormat.getDateInstance(DateFormat.LONG).format(info.getDate());

            if(info.isGlobalHash()) {
                title = getString(R.string.marker_title_retro_globalpoint, date);
            } else {
                title = getString(R.string.marker_title_retro_hashpoint, date);
            }
        }

        // The snippet's just the coordinates in question.  Further
        // details will go in the infobox.
        String snippet = UnitConverter.makeFullCoordinateString(CentralMap.this, info.getFinalLocation(), false, UnitConverter.OUTPUT_LONG);

        // Under the current marker image, the anchor is the very
        // bottom, halfway across.  Presumably, that's what the
        // default icon also uses, but we're not concerned with the
        // default icon, now, are we?
        mDestination = mMap.addMarker(new MarkerOptions()
                .position(info.getFinalDestinationLatLng())
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.final_destination))
                .anchor(0.5f, 1.0f)
                .title(title)
                .snippet(snippet));
    }

    public void requestStock(Graticule g, Calendar cal, int flags) {
        // Make sure the banner's going away!
        mBanner.animateBanner(false);

        // As a request ID, we'll use the current date.
        long date = cal.getTimeInMillis();

        Intent i = new Intent(this, StockService.class)
                .putExtra(StockService.EXTRA_DATE, cal)
                .putExtra(StockService.EXTRA_GRATICULE, g)
                .putExtra(StockService.EXTRA_REQUEST_ID, date)
                .putExtra(StockService.EXTRA_REQUEST_FLAGS, flags);

        if((flags & StockService.FLAG_USER_INITIATED) != 0)
            mStockReceiver.setWaitingId(date);

        WakefulIntentService.sendWakefulWork(this, i);
    }

    @Override
    public void onConnected(Bundle bundle) {
        // If we're coming back from somewhere, reset the marker.  This is just
        // in case the user changes coordinate preferences, as the marker only
        // updates its internal info when it's created.
        if(!isFinishing()) {
            setInfo(mCurrentInfo);
            if(!mSelectAGraticule)
                drawNearbyPoints();
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        // Since the location API doesn't appear to connect back to the network,
        // I'm not sure I need to do anything special here.  I'm not even
        // entirely convinced the connection CAN become suspended after it's
        // made unless things are completely hosed.
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        // I'm not really certain how this can fail to connect, and so I'm not
        // really certain what to do if it does.
    }

    private void zoomToIdeal(Location current) {
        // Where "current" means the user's current location, and we're zooming
        // relative to the final destination, if we have it yet.  Let's check
        // that latter part first.
        if(mCurrentInfo == null) {
            Log.i(DEBUG_TAG, "zoomToIdeal was called before an Info was set, ignoring...");
            return;
        }

        if(mGoogleClient == null || !mGoogleClient.isConnected()) {
            Log.i(DEBUG_TAG, "zoomToIdeal was called when the Google API client wasn't connected, ignoring...");
            return;
        }

        // As a side note, yes, I COULD probably mash this all down to one line,
        // but I want this to be readable later without headaches.
        LatLngBounds bounds = LatLngBounds.builder()
                .include(new LatLng(current.getLatitude(), current.getLongitude()))
                .include(mCurrentInfo.getFinalDestinationLatLng())
                .build();

        CameraUpdate cam = CameraUpdateFactory.newLatLngBounds(bounds, getResources().getDimensionPixelSize(R.dimen.map_zoom_padding));

        mMap.animateCamera(cam);
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        // If a nearby marker's info window was clicked, that means we can
        // switch to another point.
        if(mNearbyPoints.containsKey(marker)) {
            final Info newInfo = mNearbyPoints.get(marker);

            // Ask first!  Get the current location (if possible) and prompt the
            // user with a distance.
            Location lastKnown = null;
            if(mGoogleClient != null && mGoogleClient.isConnected())
                lastKnown = LocationServices.FusedLocationApi.getLastLocation(mGoogleClient);

            // Then, we've got a fragment that'll do this sort of work for us.
            DialogFragment frag = NearbyGraticuleDialogFragment.newInstance(newInfo, lastKnown);
            frag.show(getFragmentManager(), NEARBY_DIALOG);
        }
    }

    @Override
    public void onCameraChange(CameraPosition cameraPosition) {
        // We're going to check visibility on each marker individually.  This
        // might make some of them vanish while others remain on, owing to our
        // good friend the Pythagorean Theorem and neat Mercator projection
        // tricks.
        for(Marker m : mNearbyPoints.keySet())
            checkMarkerVisibility(m);
    }

    @Override
    public void onMapClick(LatLng latLng) {
        // Click!
        GraticulePickerFragment frag = (GraticulePickerFragment)getFragmentManager().findFragmentById(R.id.graticulepicker);

        // I don't know how we'd get a null here, but just in case...
        if(frag == null) return;

        // Okay, so now we've got a Graticule.  Well, we will right here:
        Graticule g = new Graticule(latLng);

        // Outline!
        outlineGraticule(g);

        // Update the fragment as well.
        frag.setNewGraticule(g);
    }

    private void outlineGraticule(Graticule g) {
        if(mGraticuleOutline != null)
            mGraticuleOutline.remove();

        mLastGraticule = g;

        if(g == null) return;

        // And with that Graticule, we can get a Polygon.
        PolygonOptions opts = g.getPolygon()
                .strokeColor(getResources().getColor(R.color.graticule_stroke))
                .strokeWidth(2)
                .fillColor(getResources().getColor(R.color.graticule_fill));

        if(mMap != null) {
            mGraticuleOutline = mMap.addPolygon(opts);

            // Also, move the map.  Zoom in as need be, cover an area of
            // roughly... oh... two graticules in any direction.
            final double CLOSENESS = 2.5;
            LatLngBounds.Builder builder = LatLngBounds.builder();
            LatLng basePoint = g.getCenterLatLng();
            LatLng point = new LatLng(basePoint.latitude - CLOSENESS, basePoint.longitude - CLOSENESS);
            builder.include(point);

            point = new LatLng(basePoint.latitude - CLOSENESS, basePoint.longitude + CLOSENESS);
            builder.include(point);

            point = new LatLng(basePoint.latitude + CLOSENESS, basePoint.longitude + CLOSENESS);
            builder.include(point);

            point = new LatLng(basePoint.latitude + CLOSENESS, basePoint.longitude - CLOSENESS);
            builder.include(point);

            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 0));
        }
    }

    private void checkMarkerVisibility(Marker m) {
        // On a camera change, we need to determine if the nearby markers
        // (assuming they exist to begin with) need to be drawn.  If they're too
        // far away, they'll get in a jumbled mess with the final destination
        // flag, and we don't want that.  This is more or less similar to the
        // clustering support in the Google Maps API v2 utilities, but since we
        // always know the markers will be in a very specific cluster, we can
        // just simplify it all into this.

        // First, if we're not in the middle of an expedition, don't worry about
        // it.
        if(mCurrentInfo != null) {
            // Figure out how far this marker is from the final point.  Hooray
            // for Pythagoras!
            Point dest = mMap.getProjection().toScreenLocation(mDestination.getPosition());
            Point mark = mMap.getProjection().toScreenLocation(m.getPosition());

            // toScreenLocation gives us values as screen pixels, not display
            // pixels.  Let's convert that to display pixels for sanity's sake.
            double dist = Math.sqrt(Math.pow((dest.x - mark.x), 2) + Math.pow(dest.y - mark.y, 2)) / mMetrics.density;

            boolean visible = true;

            // 50dp should be roughly enough.  If I need to change this later,
            // it's going to be because the images will scale by pixel density.
            if(dist < 50)
                visible = false;

            m.setVisible(visible);
        }
    }

    @Override
    public void nearbyGraticuleClicked(Info info) {
        // Info!
        setInfo(info);
        drawNearbyPoints();
    }

    private void doInitialZoom() {
        Location lastKnown = LocationServices.FusedLocationApi.getLastLocation(mGoogleClient);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean autoZoom = prefs.getBoolean(GHDConstants.PREF_AUTOZOOM, true);

        if(mAlreadyDidInitialZoom && !autoZoom) return;

        // We want the last known location to be at least SANELY recent.
        if(lastKnown != null && LocationUtil.isLocationNewEnough(lastKnown)) {
            mAlreadyDidInitialZoom = true;
            zoomToIdeal(lastKnown);
        } else {
            // Otherwise, wait for the first update and use that for an initial
            // zoom.
            mWaitingOnInitialZoom = true;
            mBanner.setErrorStatus(ErrorBanner.Status.NORMAL);
            mBanner.setText(getText(R.string.search_label).toString());
            mBanner.animateBanner(true);
            LocationRequest lRequest = LocationRequest.create();
            lRequest.setInterval(1000);
            lRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleClient, lRequest, mInitialZoomListener);
        }
    }

    private void enterSelectAGraticuleMode(boolean addFragment) {
        mSelectAGraticule = true;
        invalidateOptionsMenu();

        removeDestinationPoint();
        removeNearbyPoints();

        // We might not need the fragment to be added if the back stack has
        // been restored from somewhere.
        if(addFragment) {
            FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.setCustomAnimations(R.animator.slide_in_from_bottom,
                    R.animator.slide_out_to_bottom,
                    R.animator.slide_in_from_bottom,
                    R.animator.slide_out_to_bottom);

            GraticulePickerFragment gpf = new GraticulePickerFragment();

            // Toss in the current Graticule so the thing knows where to start.
            Bundle args = new Bundle();
            args.putParcelable("starterGraticule", mLastGraticule);
            gpf.setArguments(args);

            transaction.replace(R.id.graticulepicker, gpf, "GraticulePicker");
            transaction.addToBackStack(GRATICULE_PICKER_STACK);
            transaction.commit();
        } else {
            setInfo(mCurrentInfo);
        }

        // Highlight the last graticule.  This might be the last active
        // expedition, it might be from a rotation, whatever the case, put the
        // outline around it.
        outlineGraticule(mLastGraticule);

        mMap.setOnMapClickListener(this);
    }

    private void exitSelectAGraticuleMode() {
        if(!mSelectAGraticule) return;

        LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleClient, mFindClosestListener);

        mSelectAGraticule = false;
        invalidateOptionsMenu();
        drawNearbyPoints();

        getFragmentManager().popBackStack(GRATICULE_PICKER_STACK, FragmentManager.POP_BACK_STACK_INCLUSIVE);

        // Clean up after ourselves, too...
        mMap.setOnMapClickListener(null);
        if(mGraticuleOutline != null)
            mGraticuleOutline.remove();
        mGraticuleOutline = null;

        mLastGraticule = null;
        mLastCalendar = null;
    }

    @Override
    public void onBackPressed() {
        // If we're in Select-A-Graticule, pressing back will send us back to
        // expedition mode.  This seems obvious, especially when the default
        // implementation will close the graticule fragment anyway when the back
        // stack is popped, but we also need to do the other stuff like change
        // the menu back, stop the tap-the-map selections, etc.
        if(mSelectAGraticule)
            exitSelectAGraticuleMode();
        else
            super.onBackPressed();
    }

    @Override
    public void updateGraticule(Graticule g) {
        // Outline!
        outlineGraticule(g);

        // Stock!
        requestStock(g, Calendar.getInstance(), StockService.FLAG_USER_INITIATED | StockService.FLAG_SELECT_A_GRATICULE);
    }

    @Override
    public void findClosest() {
        // Same as with the initial zoom, only we're setting a Graticule.
        Location lastKnown = LocationServices.FusedLocationApi.getLastLocation(mGoogleClient);

        // We want the last known location to be at least SANELY recent.
        if(lastKnown != null && LocationUtil.isLocationNewEnough(lastKnown)) {
            applyFoundGraticule(lastKnown);
        } else {
            // This shouldn't be called OFTEN, but it'll probably be called.
            mBanner.setErrorStatus(ErrorBanner.Status.NORMAL);
            mBanner.setText(getText(R.string.search_label).toString());
            mBanner.animateBanner(true);
            LocationRequest lRequest = LocationRequest.create();
            lRequest.setInterval(1000);
            lRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleClient, lRequest, mFindClosestListener);
        }
    }

    @Override
    public void graticulePickerClosing() {
        exitSelectAGraticuleMode();

        doInitialZoom();
    }

    private void applyFoundGraticule(Location loc) {
        // Oh, and make sure the fragment still exists.  If it doesn't, we've
        // left Select-A-Graticule, and I'm not sure how this was called.
        GraticulePickerFragment gpf = (GraticulePickerFragment)getFragmentManager().findFragmentById(R.id.graticulepicker);

        if(gpf != null) {
            Graticule g = new Graticule(loc);
            gpf.setNewGraticule(g);
            outlineGraticule(g);
        }
    }

    /**
     * Gets the {@link ErrorBanner} we currently hold.  This is mostly for the
     * {@link CentralMapMode} classes.
     *
     * @return the current ErrorBanner
     */
    public ErrorBanner getErrorBanner() {
        return mBanner;
    }

    /**
     * Gets the {@link GoogleApiClient} we currently hold.  There's no guarantee
     * it's connected at this point, so be careful.
     *
     * @return the current GoogleApiClient
     */
    public GoogleApiClient getGoogleClient() {
        return mGoogleClient;
    }
}