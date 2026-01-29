/*
 * Copyright (c) 2025
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

/**
 * 音频静音填充配置类
 * 用于配置智能静音填充策略，避免视频加速问题
 */
public class AudioSilentFillConfig {

    /**
     * 静音填充模式
     */
    public enum SilentFillMode {
        /**
         * 模式1：极低幅度噪声
         * 填充±1到±N的随机噪声，模拟真实的环境底噪
         * 优点：最接近真实静音环境，兼容性好
         * 缺点：可能在某些播放器上听到微弱噪声
         */
        LOW_AMPLITUDE_NOISE,

        /**
         * 模式2：固定低值填充
         * 填充固定的极低值（如±1交替）
         * 优点：可预测，不会产生随机噪声
         * 缺点：可能被某些编码器优化掉
         */
        FIXED_LOW_VALUE,

        /**
         * 模式3：降低采样率填充
         * 静音时降低feed频率（每N帧feed一次）
         * 优点：减少编码负担
         * 缺点：可能导致音视频同步问题
         */
        REDUCED_SAMPLE_RATE,

        /**
         * 模式4：全0 + PTS补偿
         * Feed全0数据，但调整PTS计算逻辑
         * 优点：完全静音
         * 缺点：需要修改PTS逻辑，复杂度高
         */
        ZERO_WITH_PTS_COMPENSATION,

        /**
         * 模式5：混合策略
         * 前N秒正常feed所有数据，N秒后切换到其他模式
         * 优点：兼顾初始稳定性和后期优化
         * 缺点：逻辑复杂
         */
        HYBRID
    }

    // 默认配置
    private boolean enabled = true;  // 是否启用智能静音填充（默认启用）
    private SilentFillMode mode = SilentFillMode.LOW_AMPLITUDE_NOISE;  // 默认使用模式1
    private int noiseAmplitude = 3;  // 噪声幅度（模式1使用）
    private int skipInterval = 5;    // 跳帧间隔（模式3使用）
    private long initialPeriodMs = 10000;  // 初始期时长（模式5使用）

    // Getter/Setter方法
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SilentFillMode getMode() {
        return mode;
    }

    public void setMode(SilentFillMode mode) {
        this.mode = mode;
    }

    public int getNoiseAmplitude() {
        return noiseAmplitude;
    }

    public void setNoiseAmplitude(int noiseAmplitude) {
        this.noiseAmplitude = noiseAmplitude;
    }

    public int getSkipInterval() {
        return skipInterval;
    }

    public void setSkipInterval(int skipInterval) {
        this.skipInterval = skipInterval;
    }

    public long getInitialPeriodMs() {
        return initialPeriodMs;
    }

    public void setInitialPeriodMs(long initialPeriodMs) {
        this.initialPeriodMs = initialPeriodMs;
    }

    @Override
    public String toString() {
        return "AudioSilentFillConfig{" +
                "enabled=" + enabled +
                ", mode=" + mode +
                ", noiseAmplitude=" + noiseAmplitude +
                ", skipInterval=" + skipInterval +
                ", initialPeriodMs=" + initialPeriodMs +
                '}';
    }
}
