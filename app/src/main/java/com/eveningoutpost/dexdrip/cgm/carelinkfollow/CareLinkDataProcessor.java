package com.eveningoutpost.dexdrip.cgm.carelinkfollow;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.BloodTest;
import com.eveningoutpost.dexdrip.Models.DateUtil;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.Models.Treatments;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.UtilityModels.Inevitable;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;
import com.eveningoutpost.dexdrip.UtilityModels.PumpStatus;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.ActiveNotification;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.Marker;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.ClearedNotification;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.RecentData;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.SensorGlucose;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.TextMap;
import com.eveningoutpost.dexdrip.wearintegration.ExternalStatusService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.eveningoutpost.dexdrip.Models.BgReading.SPECIAL_FOLLOWER_PLACEHOLDER;
import static com.eveningoutpost.dexdrip.Models.Treatments.pushTreatmentSyncToWatch;

public class CareLinkDataProcessor {

    private static final String TAG = "CareLinkFollowDP";
    private static final boolean D = false;

    private static final String UUID_TAG_SENSOR_GLUCOSE = "SG";

    private static final String UUID_TAG_CARELINK_FOLLOW = "CF";
    private static final String SOURCE_CARELINK_FOLLOW = "CareLink Follow";


    static synchronized void processRecentData(final RecentData recentData, final boolean live) {

        List<SensorGlucose> filteredSgList;
        String noteText;

        //SKIP ALL IF EMPTY!!!
        if(recentData == null)
            return;

        //PUMP INFO (Pump Status)
        if(recentData.isNGP()) {
            PumpStatus.setReservoir(recentData.reservoirRemainingUnits);
            PumpStatus.setBattery(recentData.medicalDeviceBatteryLevelPercent);
            if(recentData.activeInsulin != null)
                PumpStatus.setBolusIoB(recentData.activeInsulin.amount);
            PumpStatus.syncUpdate();
        }

        //SENSOR STATUS INFO (External Status)
        if(recentData.sensorState.equals("UNKNOWN")){
            ExternalStatusService.update(JoH.tsl(), "?", true);
        } else if(recentData.sensorState.equals(RecentData.SYSTEM_STATUS_SENSOR_OFF)) {
            ExternalStatusService.update(JoH.tsl(), "-", true);
        } else {
            //Guardian Connect
            if (recentData.isGM()) {
                ExternalStatusService.update(JoH.tsl(),
                        "Cal: " + String.format("%dh", recentData.timeToNextCalibHours)
                                + " Remain: " + String.format("%dd%dh", recentData.sensorDurationHours / 24, recentData.sensorDurationHours % 24),
                        true);
                //Pump (NGP)
            } else if (recentData.isNGP()) {
                ExternalStatusService.update(JoH.tsl(),
                        "Cal: " + String.format("%dh%dm", recentData.timeToNextCalibrationMinutes / 60, recentData.sensorDurationMinutes % 24)
                                + " Remain: " + String.format("%dd%dh", recentData.sensorDurationHours / 24, recentData.sensorDurationHours % 24),
                        true);
            }
        }


        //SKIP DATA processing if NO PUMP COMMUNICATION (time shift seems to be different in this case, needs further analysis)
        if(recentData.isNGP() && !recentData.pumpCommunicationState)
            return;


        //SENSOR GLUCOSE
        if (recentData.sgs != null) {

            final BgReading lastBg = BgReading.lastNoSenssor();
            final long lastBgTimestamp = lastBg != null ? lastBg.timestamp : 0;

            //UserError.Log.d(TAG, "Create Sensor");
            final Sensor sensor = Sensor.createDefaultIfMissing();

            //SENSOR
            //Sensor status
            //CHECK SENSOR & CONNECTION PRESENT
            //sensor.latest_battery_level = connectData.medicalDeviceBatteryLevelPercent;
            //sensor.latest_battery_level
            //sensor.started_at
            //sensor.uuid
            sensor.save();

            //SENSOR GLUCOSE
            //filter SGs
            filteredSgList = new ArrayList<>();
            for (SensorGlucose sg : recentData.sgs) {
                if (sg != null) {
                    if (sg.datetime != null) { filteredSgList.add(sg);
                    } else {
                        //UserError.Log.d(TAG, "SG DateTime is null (sensor expired?)");
                    }
                } else {
                    //UserError.Log.d(TAG, "SG Entry is null!!!");
                }
            }
            // place in order of oldest first
            Collections.sort(filteredSgList, (o1, o2) -> o1.datetime.compareTo(o2.datetime));

            /*for (final SensorGlucose sg : filteredSgList) {

                //Not NULL SG (shouldn't happen?!)
                if (sg != null) {

                    //Not NULL DATETIME (sensorchange?)
                    if (sg.datetime != null) {

                        //Not EPOCH 0 (warmup?)
                        if (sg.datetime.getTime() > 1) {

                            //Not 0 SG (not calibrated?)
                            if (sg.sg > 0) {

                                final long recordTimestamp = sg.datetime.getTime();

                                //newer than last BG
                                if (recordTimestamp > lastBgTimestamp) {

                                    if (recordTimestamp > 0) {

                                        final BgReading existing = BgReading.getForPreciseTimestamp(recordTimestamp, 10_000);
                                        if (existing == null) {
                                            UserError.Log.d(TAG, "NEW NEW NEW New entry: " + sg.toS());

                                            if (live) {
                                                final BgReading bg = new BgReading();
                                                bg.timestamp = recordTimestamp;
                                                bg.calculated_value = (double) sg.sg;
                                                bg.raw_data = SPECIAL_FOLLOWER_PLACEHOLDER;
                                                bg.filtered_data = (double) sg.sg;
                                                bg.noise = "";
                                                bg.uuid = UUID.randomUUID().toString();
                                                bg.calculated_value_slope = 0;
                                                bg.sensor = sensor;
                                                bg.sensor_uuid = sensor.uuid;
                                                bg.source_info = SOURCE_CARELINK_FOLLOW;
                                                bg.save();
                                                Inevitable.task("entry-proc-post-pr", 500, () -> bg.postProcess(false));
                                            }
                                        } else {
                                            //existing entry, not needed
                                        }
                                    } else {
                                        UserError.Log.e(TAG, "Could not parse a timestamp from: " + sg.toS());
                                    }

                                }

                            } else {
                                //UserError.Log.d(TAG, "SG is 0 (calibration missed?)");
                            }

                        } else {
                            //UserError.Log.d(TAG, "SG DateTime is 0 (warmup phase?)");
                        }

                    } else {
                        //UserError.Log.d(TAG, "SG DateTime is null (sensor expired?)");
                    }

                } else {
                    //UserError.Log.d(TAG, "SG Entry is null!!!");
                }
            }*/

        } else {
            UserError.Log.d(TAG, "Recent data SGs is null!");
        }

        // LAST ALARM -> NOTE
        if(Pref.getBooleanDefaultFalse("clfollow_download_notifications")) {

            // Only Guardian Connect, NGP has all in notifications
            if (recentData.isGM() && recentData.lastAlarm != null) {

                if (recentData.lastAlarm.datetime != null && recentData.lastAlarm.kind != null) {

                    noteText = TextMap.getAlarmMessage(recentData.getDeviceFamily(), recentData.lastAlarm);

                    //New note
                    if(newNote(noteText, recentData.lastAlarm.datetime.getTime())){

                        Treatments alarm = Treatments.create_note(noteText, recentData.lastAlarm.datetime.getTime(), -1);
                        if (alarm == null) {
                            UserError.Log.d(TAG, "Create alarm baulked and returned null, so skipping");
                        } else {
                            alarm.enteredBy = SOURCE_CARELINK_FOLLOW;
                            alarm.save();
                            if (Home.get_show_wear_treatments())
                                pushTreatmentSyncToWatch(alarm, true);
                        }
                    }
                }
            }
        }

        //MARKERS -> TREATMENTS
        if(recentData.markers != null) {
            for (Marker marker : recentData.markers) {
                if (marker.type != null) {

                    //Event time
                    Date eventTime;

                    try {
                        if (marker.dateTime != null)
                            eventTime = marker.dateTime;
                        else
                            //eventTime = calcTimeByIndex(recentData.sLastSensorTime, marker.index);
                            eventTime = calcTimeByIndex(recentData.sLastSensorTime, marker.index, true);
                    } catch (Exception ex) {
                        UserError.Log.d(TAG, "Time calculation error!");
                        continue;
                    }
                    if (eventTime == null) {
                        continue;
                    }

                    //BloodGlucose, Calibration => BloodTest
                    if (marker.isBloodGlucose() && Pref.getBooleanDefaultFalse("clfollow_download_finger_bgs")) {
                        //check required valued
                        if (marker.value != null) {
                            //new blood test
                            final BloodTest existingBloodTest = BloodTest.getForPreciseTimestamp(eventTime.getTime(), 10000);
                            if (existingBloodTest == null) {
                                final BloodTest bt = BloodTest.create(eventTime.getTime(), marker.value, SOURCE_CARELINK_FOLLOW);
                                if (bt != null) {
                                    //    bt.saveit();
                                }
                            }
                        }

                        //INSULIN, MEAL => Treatment
                    } else if ((marker.type.equals(Marker.MARKER_TYPE_INSULIN) && Pref.getBooleanDefaultFalse("clfollow_download_boluses"))
                            || (marker.type.equals(Marker.MARKER_TYPE_MEAL) && Pref.getBooleanDefaultFalse("clfollow_download_meals"))) {

                        final Treatments t;
                        double carbs = 0;
                        double insulin = 0;

                        //Extract treament infos
                        if (marker.type.equals(Marker.MARKER_TYPE_INSULIN)) {
                            carbs = 0;
                            insulin = marker.deliveredExtendedAmount + marker.deliveredFastAmount;
                        } else if (marker.type.equals(Marker.MARKER_TYPE_MEAL)) {
                            carbs = marker.amount;
                            insulin = 0;
                        }

                        //new Treatment
                        if (newTreatment(carbs, insulin, eventTime.getTime())) {
                            t = Treatments.create(carbs, insulin, eventTime.getTime());
                            if (t != null) {
                                t.enteredBy = SOURCE_CARELINK_FOLLOW;
                                t.save();
                                if (Home.get_show_wear_treatments())
                                    pushTreatmentSyncToWatch(t, true);
                            }
                        }
                    }
                }

            }
        }

        //NOTIFICATIONS -> NOTE
        if(Pref.getBooleanDefaultFalse("clfollow_download_notifications")) {
            if (recentData.notificationHistory != null) {
                //Active Notifications
                if (recentData.notificationHistory.activeNotifications != null) {
                    for (ActiveNotification activeNotification : recentData.notificationHistory.activeNotifications) {
                        addNotification(activeNotification.dateTime, recentData.getDeviceFamily(), activeNotification.messageId, activeNotification.faultId);
                    }
                }
                //Cleared Notifications
                if (recentData.notificationHistory.clearedNotifications != null) {
                    for (ClearedNotification clearedNotification : recentData.notificationHistory.clearedNotifications) {
                        addNotification(clearedNotification.triggeredDateTime, recentData.getDeviceFamily(), clearedNotification.messageId, clearedNotification.faultId);
                    }
                }
            }
        }

    }

    protected static boolean addNotification(Date date, String deviceFamily, String messageId, int faultId){

        String noteText;

        //Valid date
        if(date != null) {
            //Get text
            noteText = TextMap.getNotificationMessage(deviceFamily, messageId, faultId);
            if (noteText != null) {
                //New note
                if (newNote(noteText, date.getTime())) {
                    //create_note in Treatment is not good, because of automatic link to other treatments in 5 mins range
                    //Treatments note = Treatments.create_note(noteText, date.getTime(), -1);
                    //if (note != null) {
                    Treatments note = new Treatments();
                    note.notes = noteText;
                    note.timestamp = date.getTime();
                    note.created_at = DateUtil.toISOString(note.timestamp);
                    note.uuid = UUID.randomUUID().toString();
                    note.enteredBy = SOURCE_CARELINK_FOLLOW;
                    note.save();
                    if (Home.get_show_wear_treatments())
                        pushTreatmentSyncToWatch(note, true);
                    return  true;
                    //}
                }
            }
        }

        return false;

    }

    protected static boolean newNote(String note, long timestamp){

        List<Treatments> treatmentsList;
        //Treatment with same timestamp and note text exists?
        treatmentsList = Treatments.listByTimestamp(timestamp);
        if(treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.notes.contains(note))
                    return  false;
            }
        }
        return  true;

    }

    protected static boolean newTreatment(double carbs, double insulin, long timestamp){

        List<Treatments> treatmentsList;
        //Treatment with same timestamp and carbs + insulin exists?
        treatmentsList = Treatments.listByTimestamp(timestamp);
        if(treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.carbs == carbs && treatments.insulin == insulin)
                    return  false;
            }
        }
        return  true;
    }

    protected static boolean newInsulin(double insulin, long timestamp){

        List<Treatments> treatmentsList;
        //Treatment with same timestamp and insulin exists?
        treatmentsList = Treatments.listByTimestamp(timestamp);
        if(treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.insulin == insulin)
                    return  false;
            }
        }
        return  true;
    }

    protected static boolean newCarbs(double carbs, long timestamp){

        List<Treatments> treatmentsList;

        treatmentsList = Treatments.listByTimestamp(timestamp);

        if(treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.carbs == carbs)
                    return  false;
            }
        }

        return  true;
    }


 /*
    protected static Date calcTimeByIndex(Date lastSensorTime, int index){
        if(lastSensorTime == null)
            return null;
        else
            return new Date((lastSensorTime.getTime() - ((287 - index) * 300_000L)));
    }

 */

    protected static Date calcTimeByIndex(Date lastSensorTime, int index, boolean round){
        if(lastSensorTime == null)
            return null;
        else if(round)
            //round to 10 minutes
            return new Date((Math.round((calcTimeByIndex(lastSensorTime,index,false).getTime()) / 600_000D) * 600_000L));
        else
            return new Date((lastSensorTime.getTime() - ((287 - index) * 300_000L)));
    }

}
