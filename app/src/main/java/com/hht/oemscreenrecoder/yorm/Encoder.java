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

import java.io.IOException;

/**
 * @author yrom
 * @version 2017/12/4
 */
interface Encoder {
    void prepare() throws IOException;

    void stop();

    void release();

    void setCallback(Callback callback);

    interface Callback {
        void onError(Encoder encoder, Exception exception);

        /**
         * 当检测到系统不支持内置声音录制时调用（仅用于MicRecorder）
         * 用于通知上层显示Toast提示用户
         * @param audioType 音频类型：0=MIC, 1=INTERNAL, 2=MIC_AND_INTERNAL
         */
        default void onInternalAudioNotAvailable(int audioType) {
            // 默认空实现，子类可选择性覆盖
        }
    }
}
