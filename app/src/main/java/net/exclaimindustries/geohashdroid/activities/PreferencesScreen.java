/*
 * SettingsActivity.java
 * Copyright (C) 2015 Nicholas Killewald
 *
 * This file is distributed under the terms of the BSD license.
 * The source package should have a LICENSE file at the toplevel.
 */

package net.exclaimindustries.geohashdroid.activities;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.backup.BackupManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.commonsware.cwac.wakeful.WakefulIntentService;
import com.google.android.gms.maps.model.LatLng;

import net.exclaimindustries.geohashdroid.R;
import net.exclaimindustries.geohashdroid.services.AlarmService;
import net.exclaimindustries.geohashdroid.services.WikiService;
import net.exclaimindustries.geohashdroid.util.GHDConstants;
import net.exclaimindustries.geohashdroid.util.HashBuilder;
import net.exclaimindustries.geohashdroid.util.KnownLocation;
import net.exclaimindustries.tools.QueueService;

import java.util.LinkedList;
import java.util.List;

/**
 * <p>
 * So, the actual Android class is already called "{@link PreferenceActivity}",
 * it turns out.  So let's call this one <code>PreferencesScreen</code>, because
 * it got really confusing to call it <code>PreferencesActivity</code> like it
 * used to be.
 * </p>
 *
 * <p>
 * Note that this doesn't inherit from BaseGHDThemeActivity.  It has to
 * implement all of that itself.
 * </p>
 */
public class PreferencesScreen extends PreferenceActivity {
    private boolean mStartedInNight = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mStartedInNight = prefs.getBoolean(GHDConstants.PREF_NIGHT_MODE, false);

        // We have to do this BEFORE any layouts are set up.
        if(mStartedInNight)
            setTheme(R.style.Theme_GeohashDroidDark);
        else
            setTheme(R.style.Theme_GeohashDroid);

        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If the nightiness has changed since we paused, do a recreate.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if(prefs.getBoolean(GHDConstants.PREF_NIGHT_MODE, false) != mStartedInNight)
            recreate();
    }

    /**
     * This largely comes from Android Studio's default Setting Activity wizard
     * thingamajig.  It conveniently updates preferences with summaries.
     */
    private static Preference.OnPreferenceChangeListener mSummaryUpdater = new Preference.OnPreferenceChangeListener() {

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            // The basic stringy version of the value.
            String stringValue = newValue.toString();

            if(preference instanceof ListPreference) {
                // For list preferences, look up the correct display value in
                // the preference's 'entries' list.
                ListPreference listPreference = (ListPreference) preference;
                int index = listPreference.findIndexOfValue(stringValue);

                // Set the summary to reflect the new value.
                preference.setSummary(
                        index >= 0
                                ? listPreference.getEntries()[index]
                                : null);
            } else {
                // Eh, just use the string value.  That's simple enough.
                preference.setSummary(stringValue);
            }
            return true;
        }
    };

    /**
     * Also from Android Studio, this attaches a preference to the summary
     * updater.
     */
    private static void bindPreferenceSummaryToValue(Preference preference) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(mSummaryUpdater);

        // Trigger the listener immediately with the preference's current value.
        mSummaryUpdater.onPreferenceChange(preference,
                PreferenceManager
                        .getDefaultSharedPreferences(preference.getContext())
                        .getString(preference.getKey(), ""));
    }

    @Override
    public void onBuildHeaders(List<Header> target) {
        // Let's Honeycomb these preferences right up.  Headers!
        loadHeadersFromResource(R.xml.pref_headers, target);
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        return(fragmentName.equals(MapPreferenceFragment.class.getName())
            || fragmentName.equals(WikiPreferenceFragment.class.getName())
            || fragmentName.equals(OtherPreferenceFragment.class.getName())
            || fragmentName.equals(DebugFragment.class.getName()));
    }

    /**
     * These are your garden-variety map preferences, assuming your garden is on
     * the map somewhere.
     */
    public static class MapPreferenceFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_map);

            bindPreferenceSummaryToValue(findPreference(GHDConstants.PREF_DIST_UNITS));
            bindPreferenceSummaryToValue(findPreference(GHDConstants.PREF_COORD_UNITS));
            bindPreferenceSummaryToValue(findPreference(GHDConstants.PREF_STARTUP_BEHAVIOR));

            // The known locations manager is just another Activity.
            findPreference("_knownLocations").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent i = new Intent(getActivity(), KnownLocationsPicker.class);
                    startActivity(i);
                    return true;
                }
            });
        }

        @Override
        public void onStop() {
            BackupManager bm = new BackupManager(getActivity());
            bm.dataChanged();

            super.onStop();
        }
    }

    /**
     * These are the preferences you'll be seeing way too often if you keep
     * getting your wiki password wrong.
     */
    public static class WikiPreferenceFragment extends PreferenceFragment {
        /**
         * This keeps track of whether or not the wiki username and/or password
         * have changed.  If so, we need to ask WikiService to resume itself, as
         * the user might've come here to resolve a bad login error.
         */
        private boolean mHasChanged = false;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_wiki);

            // Unfortunately, we can't use the otherwise-common binding method
            // for username and password, owing to the extra boolean we need to
            // track.  Worse, since we don't want to update the summary for
            // password (for obvious reasons), we can't even share the same
            // object between the two preferences.  Well, we CAN, but that won't
            // really buy us much in terms of efficiency.
            Preference usernamePref = findPreference(GHDConstants.PREF_WIKI_USER);
            usernamePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    preference.setSummary(newValue.toString());
                    mHasChanged = true;
                    return true;
                }
            });
            usernamePref.setSummary(PreferenceManager.getDefaultSharedPreferences(getActivity()).getString(GHDConstants.PREF_WIKI_USER, ""));

            findPreference(GHDConstants.PREF_WIKI_PASS).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    mHasChanged = true;
                    return true;
                }
            });
        }

        @Override
        public void onStop() {
            // If something changed, tell WikiService to kick back in.  Don't
            // worry; if WikiService isn't paused, this won't do anything, and
            // if it's stopped for any other reason, it'll stop again when this
            // comes in.
            if(mHasChanged) {
                mHasChanged = false;
                Intent i = new Intent(getActivity(), WikiService.class);
                i.putExtra(QueueService.COMMAND_EXTRA, QueueService.COMMAND_RESUME);
                getActivity().startService(i);
            }

            BackupManager bm = new BackupManager(getActivity());
            bm.dataChanged();

            super.onStop();
        }
    }

    /**
     * These preferences are outcasts, and nobody likes them.
     */
    public static class OtherPreferenceFragment extends PreferenceFragment {
        private static final String WIPE_DIALOG = "wipeDialog";
        private static final String RESET_BUGGING_ME_DIALOG = "resetBuggingMe";

        /**
         * This is the {@link DialogFragment} that shows up when the user wants
         * to wipe the stock cache, just to make really really sure the user
         * really wants to do so.
         */
        public static class WipeCacheDialogFragment extends DialogFragment {
            @Override
            public Dialog onCreateDialog(Bundle savedInstanceState) {
                return new AlertDialog.Builder(getActivity()).setMessage(R.string.pref_stockwipe_dialog_text)
                        .setTitle(R.string.pref_stockwipe_title)
                        .setPositiveButton(R.string.dialog_stockwipe_yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // Well, you heard the orders!
                                dismiss();

                                if(HashBuilder.deleteCache(getActivity())) {
                                    Toast.makeText(
                                            getActivity(),
                                            R.string.toast_stockwipe_success,
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(
                                            getActivity(),
                                            R.string.toast_stockwipe_failure,
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton(R.string.dialog_stockwipe_no, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                            }
                        })
                        .create();
            }
        }

        public static class ResetBuggingMeDialogFragment extends DialogFragment {
            @Override
            public Dialog onCreateDialog(Bundle savedInstanceState) {
                return new AlertDialog.Builder(getActivity()).setMessage(R.string.pref_reset_butting_me_dialog_text)
                        .setTitle(R.string.pref_reset_bugging_me_title)
                        .setPositiveButton(R.string.dialog_reset_bugging_me_yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // Well, you heard the orders!
                                dismiss();

                                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
                                SharedPreferences.Editor editor = prefs.edit();

                                // This list will grow and grow as I keep adding
                                // in new prompts until I get sick of it and
                                // come up with a more efficient way to do this.
                                editor.putBoolean(GHDConstants.PREF_STOP_BUGGING_ME_PREFETCH_WARNING, false);
                                editor.apply();

                                Toast.makeText(
                                        getActivity(),
                                        R.string.toast_reset_bugging_me_success,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(R.string.dialog_reset_bugging_me_no, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                            }
                        })
                        .create();
            }
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_other);

            bindPreferenceSummaryToValue(findPreference(GHDConstants.PREF_STOCK_CACHE_SIZE));

            // The stock alarm preference needs to enable/disable the alarm as
            // need be.
            findPreference(GHDConstants.PREF_STOCK_ALARM).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

                @Override
                public boolean onPreferenceChange(Preference preference,
                                                  Object newValue) {
                    if(newValue instanceof Boolean) {
                        Boolean set = (Boolean) newValue;

                        Intent i = new Intent(getActivity(), AlarmService.class);

                        if(set) {
                            // ON!  Start the service!
                            i.setAction(AlarmService.STOCK_ALARM_ON);
                        } else {
                            // OFF!  Stop the service and cancel all alarms!
                            i.setAction(AlarmService.STOCK_ALARM_OFF);
                        }

                        WakefulIntentService.sendWakefulWork(getActivity(), i);
                    }

                    return true;
                }

            });

            // Cache wiping is more a button than a preference, per se.
            findPreference("_stockWipe").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    DialogFragment frag = new WipeCacheDialogFragment();
                    frag.show(getFragmentManager(), WIPE_DIALOG);
                    return true;
                }
            });

            // As is the reminder unremindening.
            findPreference("_resetBuggingMe").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    DialogFragment frag = new ResetBuggingMeDialogFragment();
                    frag.show(getFragmentManager(), RESET_BUGGING_ME_DIALOG);
                    return true;
                }
            });
        }

        @Override
        public void onStop() {
            BackupManager bm = new BackupManager(getActivity());
            bm.dataChanged();

            super.onStop();
        }
    }

    /**
     * These preferences aren't real, and should only exist on the logcat
     * branch.
     */
    public static class DebugFragment extends PreferenceFragment {
        private static final String DEBUG_TAG = "DebugFragment";

        private Preference.OnPreferenceClickListener _fillClicker = new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                // Our plan is to just flood the graticule-sized area with an
                // 11x11 grid of 10km Known Locations, separated by .1 degrees
                // (that roughly kinda works).  So, first let's get the base
                // coordinates.
                double baseLat, baseLon;
                switch(preference.getKey()) {
                    case "_debug_non30w":
                        // Non-30W... how about the Lexington, KY graticule?
                        // If you don't like this, then get me a better job
                        // somewhere else and maybe I'll change it.  Start at
                        // -85 and work your way RIGHT to make the 38N 84E
                        // graticule.
                        baseLat = 38;
                        baseLon = -85;
                        break;
                    case "_debug_30w":
                        // A 30W graticule... let's go with Bathurst, Australia,
                        // as that's the graticule in question for the bug I was
                        // chasing down when I made this.  Start at -34 and work
                        // your way UP to make the 33S 149W graticule.
                        baseLat = -34;
                        baseLon = 149;
                        break;
                    case "_debug_meridian":
                        // The Prime Meridian isn't really a graticule, per se.
                        // In order to wrap around it for testing purposes, we
                        // need to put it in half-graticule portions.  But let's
                        // make it around London anyway.
                        baseLat = 51;
                        baseLon = -0.5;
                        break;
                    default:
                        Log.e(DEBUG_TAG, preference.getKey() + " isn't a valid debug location filler!");
                        return true;
                }

                // This is debug-land, so to determine if we've already filled a
                // graticule, we'll just see if the top-corner has a location.
                List<KnownLocation> locations = KnownLocation.getAllKnownLocations(getActivity());

                // Unfortunately, the locations aren't organized at all.  Oops.
                for(KnownLocation kl : locations) {
                    LatLng loc = kl.getLatLng();
                    if(loc.latitude == baseLat && loc.longitude == baseLon)
                    {
                        Toast.makeText(
                                getActivity(),
                                R.string.pref_debug_locations_exist,
                                Toast.LENGTH_SHORT).show();
                        return true;
                    }
                }

                // If not, it's time to build locations!  121 of them!
                for(double lat = baseLat; lat <= baseLat + 1; lat += .1) {
                    for(double lon = baseLon; lon <= baseLon + 1; lon += .1) {
                        KnownLocation kl = new KnownLocation("Debug " + lat + ", " + lon + " location", new LatLng(lat, lon), 10000, false);
                        locations.add(kl);
                    }
                }

                // Built!  Toss 'em in!
                KnownLocation.storeKnownLocations(getActivity(), locations);

                Toast.makeText(
                        getActivity(),
                        R.string.pref_debug_locations_added,
                        Toast.LENGTH_SHORT).show();

                return true;
            }
        };

        private Preference.OnPreferenceClickListener _wipeClicker = new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                // Look, if you wanted to keep your locations, maybe you should
                // come up with a better name than "Debug" if you're using the
                // logcat branch.
                List<KnownLocation> locations = KnownLocation.getAllKnownLocations(getActivity());
                List<KnownLocation> toDelete = new LinkedList<>();

                for(KnownLocation kl : locations) {
                    if(kl.getName().startsWith("Debug"))
                        toDelete.add(kl);
                }

                for(KnownLocation kl : toDelete) {
                    locations.remove(kl);
                }

                KnownLocation.storeKnownLocations(getActivity(), locations);

                Toast.makeText(
                        getActivity(),
                        R.string.pref_debug_locations_wiped,
                        Toast.LENGTH_SHORT).show();

                return true;
            }
        };

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_debug);

            // Seriously, none of these preferences are real.  They're all more
            // of things you poke to make things happen.
            findPreference("_debug_non30w").setOnPreferenceClickListener(_fillClicker);
            findPreference("_debug_30w").setOnPreferenceClickListener(_fillClicker);
            findPreference("_debug_meridian").setOnPreferenceClickListener(_fillClicker);

            findPreference("_debug_wipeLocations").setOnPreferenceClickListener(_wipeClicker);

            findPreference("_debug_stockAlarm").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // PARTY ALARM!!!
                    Intent i = new Intent("net.exclaimindustries.geohashdroid.STOCK_ALARM");
                    i.setClass(getActivity(), AlarmService.class);
                    WakefulIntentService.sendWakefulWork(getActivity(), i);

                    Toast.makeText(
                            getActivity(),
                            R.string.pref_debug_stock_alarm,
                            Toast.LENGTH_SHORT).show();

                    return true;
                }
            });
        }
    }
}
