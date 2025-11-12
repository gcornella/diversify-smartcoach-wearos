package com.example.kurtosisstudy;
public final class PrefsKeys {

    private PrefsKeys() {} // no instances

    public static final class Settings {
        public static final String SETTINGS_PREFS = "settings_prefs";
        public static final String USER_ID = "user_id";
        public static final String HANDEDNESS = "handedness";
        private Settings() {}
    }

    public static final class HeartBeat {
        public static final String HEARTBEAT_PREFS = "heartbeat_prefs";
        public static final String HEARTBEAT_TIME = "heartbeat_time";
        private HeartBeat() {}
    }

    public static final class Data {
        public static final String PREFS = "data_storage_prefs";
        public static final String STUDY_START_TIME = "study_start_time";
        public static final String LAST_USER_ID = "last_user_id";
        public static final String LAST_CLOUD_UPLOAD_TIME = "last_cloud_upload_time";
        public static final String WEEK_ID = "week_id";
        public static final String CURRENT_DB_NAME = "current_db_name";
        public static final String TODAY_DATE = "today_date";
        public static final String LAST_KNOWN_PROGRESS = "last_known_progress";
        public static final String LAST_KNOWN_GOAL = "last_known_goal";
        public static final String LAST_KNOWN_RATIO = "last_known_ratio";
        private Data() {}
    }

    public static final class Wear {
        public static final String WEAR_PREFS = "wear_prefs";
        public static final String WEAR_STATE = "wear_state";
        public static final String WEAR_BOOL = "watch_is_worn";
        public static final String LAST_WEAR_NOTIF = "last_wear_notif";
        private Wear() {}
    }

    public static final class Exercise {
        public static final String EXERCISE_PREFS = "exercise_prefs";
        public static final String LAST_INDEX = "last_index";
        public static final String LAST_SWITCH_TIME = "last_switch_time";
        private Exercise() {}
    }

    public static final class FGS {
        public static final String FGS_PREFS = "fgs_prefs";
        public static final String LAST_WEAR_TIME = "last_daily_wear_time_update_timestamp";
        public static final String LAST_HOURLY_SAVE = "last_hourly_save_task_timestamp";
        public static final String LAST_SENSOR_CHECK = "last_all_sensors_check_timestamp";
        public static final String LAST_BATTERY_CHECK = "last_battery_check_timestamp";
        private FGS() {}
    }

    public static final class Notif {
        public static final String NOTIF_PREFS = "notifications_prefs";
        public static final String LAST_DAY_CHECKED = "last_day_checked";
        public static final String GOAL_REACHED_TODAY = "goal_reached_today";
        public static final String NOTIFS_SHOWED = "notifications_showed";
        public static final String LAST_NOTIF_TIME = "last_notification_timestamp";
        private Notif() {}
    }
}
