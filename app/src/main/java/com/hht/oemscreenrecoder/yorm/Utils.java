/*
 * Copyright (c) 2017 Yrom Wang <http://www.yrom.net>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hht.oemscreenrecoder.yorm;

import static com.hht.oemscreenrecoder.yorm.ScreenRecorder.AUDIO_AAC;
import static com.hht.oemscreenrecoder.yorm.ScreenRecorder.VIDEO_AVC;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.AsyncTask;
import android.util.Log;
import android.util.Range;
import android.util.SparseArray;
import android.widget.ArrayAdapter;
import android.widget.SpinnerAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Utils {

    public static MediaCodecInfo[] mAvcCodecInfos; // avc codecs
    public static MediaCodecInfo[] mAacCodecInfos; // aac codecs

    public static void init() {
        Log.i("liyiwei","--------------init Encoders---------------");
        findEncodersByTypeAsync(VIDEO_AVC, infos -> {
            logCodecInfos(infos, VIDEO_AVC);
            mAvcCodecInfos = infos;
        });
        findEncodersByTypeAsync(AUDIO_AAC, infos -> {
            logCodecInfos(infos, AUDIO_AAC);
            mAacCodecInfos = infos;
        });
    }

    public static MediaCodecInfo getAudioCodecInfo(String codecName) {
        if (codecName == null) return null;
        if (mAacCodecInfos == null) {
            mAacCodecInfos = Utils.findEncodersByType(AUDIO_AAC);
        }
        MediaCodecInfo codec = null;
        for (int i = 0; i < mAacCodecInfos.length; i++) {
            MediaCodecInfo info = mAacCodecInfos[i];
            if (info.getName().equals(codecName)) {
                codec = info;
                break;
            }
        }
        if (codec == null) return null;
        return codec;
    }

    public static Integer resetAudioBitrateAdapter(MediaCodecInfo.CodecCapabilities capabilities,int pos) {
        Range<Integer> bitrateRange = capabilities.getAudioCapabilities().getBitrateRange();
        int lower = Math.max(bitrateRange.getLower() / 1000, 80);
        int upper = bitrateRange.getUpper() / 1000;
        List<Integer> rates = new ArrayList<>();
        for (int rate = lower; rate < upper; rate += lower) {
            rates.add(rate);
        }
        rates.add(upper);
        if (pos<0){
            return rates.get(rates.size() / 2);
        }else {
            return rates.get(pos);
        }
    }

    public static Integer resetSampleRateAdapter(MediaCodecInfo.CodecCapabilities capabilities) {
        int[] sampleRates = capabilities.getAudioCapabilities().getSupportedSampleRates();
        List<Integer> rates = new ArrayList<>(sampleRates.length);
        int preferred = -1;
        for (int i = 0; i < sampleRates.length; i++) {
            int sampleRate = sampleRates[i];
            if (sampleRate == 44100) {
                preferred = i;
            }
            rates.add(sampleRate);
        }

        return rates.get(preferred);
    }

    public static  int getSelectedAudioProfile(int pos) {
        if (Utils.aacProfiles() == null) throw new IllegalStateException();
        String selectedItem = Utils.aacProfiles()[pos];
        MediaCodecInfo.CodecProfileLevel profileLevel = Utils.toProfileLevel(selectedItem);
        return profileLevel == null ? MediaCodecInfo.CodecProfileLevel.AACObjectMain : profileLevel.profile;
    }

    public static MediaCodecInfo getVideoCodecInfo(String codecName) {
        if (codecName == null) return null;
        if (mAvcCodecInfos == null) {
            mAvcCodecInfos = Utils.findEncodersByType(VIDEO_AVC);
        }
        MediaCodecInfo codec = null;
        for (int i = 0; i < mAvcCodecInfos.length; i++) {
            MediaCodecInfo info = mAvcCodecInfos[i];
            if (info.getName().equals(codecName)) {
                codec = info;
                break;
            }
        }
        if (codec == null) return null;
        return codec;
    }

    public static void onCheckResolutionAndFramerate(String codecName, int width,int height,int selectedFramerate) {
        MediaCodecInfo codec = getVideoCodecInfo(codecName);
        if (codec == null) return;
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
        if (!videoCapabilities.isSizeSupported(width, height)) {
//            toast(getString(R.string.codec_unsupported_size),
//                    codecName, width, height, mOrientation.getSelectedItem());
            Log.e(TAG,"codec_unsupported_size codecName=="+codecName+" width:"+width+" height:"+height);
            Log.w("@@", codecName +
                    " height range: " + videoCapabilities.getSupportedHeights() +
                    "\n width range: " + videoCapabilities.getSupportedHeights());
        } else if (!videoCapabilities.areSizeAndRateSupported(width, height, selectedFramerate)||
                !videoCapabilities.getSupportedFrameRates().contains(selectedFramerate)) {
//            toast(getString(R.string.codec_unsupported_size_with_framerate),
//                    codecName, width, height, mOrientation.getSelectedItem(), (int) selectedFramerate);
            Log.e(TAG,"codec_unsupported_size_with_framerate codecName=="+codecName+" width:"+width+" height:"+height+" selectedFramerate:"+selectedFramerate);
        }
    }

    public static  void onCheckBitrateChanged(String codecName, int selectedBitrate) {
        MediaCodecInfo codec = getVideoCodecInfo(codecName);
        if (codec == null) return;
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();

        if (!videoCapabilities.getBitrateRange().contains(selectedBitrate)) {
            Log.e(TAG,"codec_unsupported_bitrate codecName=="+codecName+" selectedBitrate:"+selectedBitrate);
//            toast(getString(R.string.codec_unsupported_bitrate), codecName, selectedBitrate);
            Log.w("@@", codecName +
                    " bitrate range: " + videoCapabilities.getBitrateRange());
        }
    }

    /**
     * Print information of all MediaCodec on this device.
     */
    public static void logCodecInfos(MediaCodecInfo[] codecInfos, String mimeType) {
        for (MediaCodecInfo info : codecInfos) {
            StringBuilder builder = new StringBuilder(512);
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mimeType);
            builder.append("Encoder '").append(info.getName()).append('\'')
                    .append("\n  supported : ")
                    .append(Arrays.toString(info.getSupportedTypes()));
            MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
            if (videoCaps != null) {
                builder.append("\n  Video capabilities:")
                        .append("\n  Widths: ").append(videoCaps.getSupportedWidths())
                        .append("\n  Heights: ").append(videoCaps.getSupportedHeights())
                        .append("\n  Frame Rates: ").append(videoCaps.getSupportedFrameRates())//帧率，视频每秒传输的帧数（画面数），每秒帧数越多，显示的画面就越流畅，但是码率恒定帧率增加，画质则会降低
                        .append("\n  Bitrate: ").append(videoCaps.getBitrateRange());//比特率越高，每秒传送数据就越多，画质就越清晰，视频文件占用空间也越大。
                if (VIDEO_AVC.equals(mimeType)) {
                    MediaCodecInfo.CodecProfileLevel[] levels = caps.profileLevels;

                    builder.append("\n  Profile-levels: ");

                    for (MediaCodecInfo.CodecProfileLevel level : levels) {
                        builder.append("\n  ").append(Utils.avcProfileLevelToString(level));
                    }
                }
                builder.append("\n  Color-formats: ");//编码前源颜色格式
                for (int c : caps.colorFormats) {
                    builder.append("\n  ").append(Utils.toHumanReadable(c));
                }
            }
            MediaCodecInfo.AudioCapabilities audioCaps = caps.getAudioCapabilities();
            if (audioCaps != null) {
                builder.append("\n Audio capabilities:")
                        .append("\n Sample Rates: ").append(Arrays.toString(audioCaps.getSupportedSampleRates()))
                        .append("\n Bit Rates: ").append(audioCaps.getBitrateRange())
                        .append("\n Max channels: ").append(audioCaps.getMaxInputChannelCount());
            }
            Log.i("@@@", builder.toString());
        }
    }

    public interface Callback {
        void onResult(MediaCodecInfo[] infos);
    }

    public static final class EncoderFinder extends AsyncTask<String, Void, MediaCodecInfo[]> {
        private Callback func;

        EncoderFinder(Callback func) {
            this.func = func;
        }

        @Override
        protected MediaCodecInfo[] doInBackground(String... mimeTypes) {
            return findEncodersByType(mimeTypes[0]);
        }

        @Override
        protected void onPostExecute(MediaCodecInfo[] mediaCodecInfos) {
            func.onResult(mediaCodecInfos);
        }
    }

    public static void findEncodersByTypeAsync(String mimeType, Callback callback) {
        new EncoderFinder(callback).execute(mimeType);
    }

    public static final String TAG = "Utils";
    /**
     * Find an encoder supported specified MIME type
     *
     * @return Returns empty array if not found any encoder supported specified MIME type
     */

    public static MediaCodecInfo[] findEncodersByType(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        List<MediaCodecInfo> infos = new ArrayList<>();
        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            try {
                MediaCodecInfo.CodecCapabilities cap = info.getCapabilitiesForType(mimeType);
                Log.d(TAG, "findEncodersByType: "+info.getName());
                Log.d(TAG, "getSupportedTypes: "+ Arrays.toString(info.getSupportedTypes()));
                if (cap == null) continue;
            } catch (IllegalArgumentException e) {
                // unsupported
                continue;
            }
            infos.add(info);
        }

        return infos.toArray(new MediaCodecInfo[infos.size()]);
    }


    public static SparseArray<String> sAACProfiles = new SparseArray<>();
    public static SparseArray<String> sAVCProfiles = new SparseArray<>();
    public static SparseArray<String> sAVCLevels = new SparseArray<>();


    public static String resetAvcProfileLevelAdapter(MediaCodecInfo.CodecCapabilities capabilities,int pos) {
        MediaCodecInfo.CodecProfileLevel[] profiles = capabilities.profileLevels;
        if (profiles == null || profiles.length == 0) {
            return "Default";
        }

        String[] profileLevels = new String[profiles.length + 1];
        profileLevels[0] = "Default";
        for (int i = 0; i < profiles.length; i++) {
            Log.i(TAG,i+" ： profiles ="+Utils.avcProfileLevelToString(profiles[i]));
            profileLevels[i + 1] = Utils.avcProfileLevelToString(profiles[i]);
        }

        return profileLevels[pos];
    }

    /**
     * @param avcProfileLevel AVC CodecProfileLevel
     */
   public static String avcProfileLevelToString(MediaCodecInfo.CodecProfileLevel avcProfileLevel) {
        if (sAVCProfiles.size() == 0 || sAVCLevels.size() == 0) {
            initProfileLevels();
        }
        String profile = null, level = null;
        int i = sAVCProfiles.indexOfKey(avcProfileLevel.profile);
        if (i >= 0) {
            profile = sAVCProfiles.valueAt(i);
        }

        i = sAVCLevels.indexOfKey(avcProfileLevel.level);
        if (i >= 0) {
            level = sAVCLevels.valueAt(i);
        }

        if (profile == null) {
            profile = String.valueOf(avcProfileLevel.profile);
        }
        if (level == null) {
            level = String.valueOf(avcProfileLevel.level);
        }
        return profile + '-' + level;
    }

    public static String[] aacProfiles() {
        if (sAACProfiles.size() == 0) {
            initProfileLevels();
        }
        String[] profiles = new String[sAACProfiles.size()];
        for (int i = 0; i < sAACProfiles.size(); i++) {
            profiles[i] = sAACProfiles.valueAt(i);
        }
        return profiles;
    }

    public static MediaCodecInfo.CodecProfileLevel toProfileLevel(String str) {
        if (sAVCProfiles.size() == 0 || sAVCLevels.size() == 0 || sAACProfiles.size() == 0) {
            initProfileLevels();
        }
        String profile = str;
        String level = null;
        int i = str.indexOf('-');
        if (i > 0) { // AVC profile has level
            profile = str.substring(0, i);
            level = str.substring(i + 1);
        }

        MediaCodecInfo.CodecProfileLevel res = new MediaCodecInfo.CodecProfileLevel();
        if (profile.startsWith("AVC")) {
            res.profile = keyOfValue(sAVCProfiles, profile);
        } else if (profile.startsWith("AAC")) {
            res.profile = keyOfValue(sAACProfiles, profile);
        } else {
            try {
                res.profile = Integer.parseInt(profile);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        if (level != null) {
            if (level.startsWith("AVC")) {
                res.level = keyOfValue(sAVCLevels, level);
            } else {
                try {
                    res.level = Integer.parseInt(level);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        return res.profile > 0 && res.level >= 0 ? res : null;
    }

    public static <T> int keyOfValue(SparseArray<T> array, T value) {
        int size = array.size();
        for (int i = 0; i < size; i++) {
            T t = array.valueAt(i);
            if (t == value || t.equals(value)) {
                return array.keyAt(i);
            }
        }
        return -1;
    }

    public static void initProfileLevels() {
        Field[] fields = MediaCodecInfo.CodecProfileLevel.class.getFields();
        for (Field f : fields) {
            if ((f.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) == 0) {
                continue;
            }
            String name = f.getName();
            SparseArray<String> target;
            if (name.startsWith("AVCProfile")) {
                target = sAVCProfiles;
            } else if (name.startsWith("AVCLevel")) {
                target = sAVCLevels;
            } else if (name.startsWith("AACObject")) {
                target = sAACProfiles;
            } else {
                continue;
            }
            try {
                target.put(f.getInt(null), name);
            } catch (IllegalAccessException e) {
                //ignored
            }
        }
    }


    public static SparseArray<String> sColorFormats = new SparseArray<>();

    public static String toHumanReadable(int colorFormat) {
        if (sColorFormats.size() == 0) {
            initColorFormatFields();
        }
        int i = sColorFormats.indexOfKey(colorFormat);
        if (i >= 0) return sColorFormats.valueAt(i);
        return "0x" + Integer.toHexString(colorFormat);
    }

    public static int toColorFormat(String str) {
        if (sColorFormats.size() == 0) {
            initColorFormatFields();
        }
        int color = keyOfValue(sColorFormats, str);
        if (color > 0) return color;
        if (str.startsWith("0x")) {
            return Integer.parseInt(str.substring(2), 16);
        }
        return 0;
    }

    public static void initColorFormatFields() {
        // COLOR_
        Field[] fields = MediaCodecInfo.CodecCapabilities.class.getFields();
        for (Field f : fields) {
            if ((f.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) == 0) {
                continue;
            }
            String name = f.getName();
            if (name.startsWith("COLOR_")) {
                try {
                    int value = f.getInt(null);
                    sColorFormats.put(value, name);
                } catch (IllegalAccessException e) {
                    // ignored
                }
            }
        }

    }
}
