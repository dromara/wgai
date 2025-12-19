<template>
  <div class="image-recognition-container">
    <!-- 简洁背景 -->
    <div class="clean-background">
      <div class="gradient-circle circle-1"></div>
      <div class="gradient-circle circle-2"></div>
      <div class="grid-pattern"></div>
    </div>

    <!-- 主内容区 -->
    <div class="content-wrapper">
      <a-row :gutter="20" type="flex" class="main-row">
        <!-- 左侧：上传区域 -->
        <a-col :span="10" class="left-section">
          <div class="upload-panel">
            <!-- 标题区 -->
            <div class="panel-header">
              <div class="header-icon">
                <a-icon type="picture" />
              </div>
              <div class="header-content">
                <h3 class="panel-title">图片识别</h3>
                <p class="panel-subtitle">上传图片,AI 智能分析</p>
              </div>
            </div>

            <!-- 模型选择 -->
            <div class="form-section">
              <div class="form-label">
                <a-icon type="robot" class="label-icon" />
                <span>选择识别模型</span>
              </div>
              <div class="select-wrapper">
                <j-search-select-tag 
                  v-model="modelId" 
                  dict="tab_ai_model,end_name,id,model_dify=20" 
                  placeholder="请选择 AI 识别模型"
                  size="large"
                />
              </div>
            </div>

            <!-- 图片上传 -->
            <div class="form-section">
              <div class="form-label">
                <a-icon type="cloud-upload" class="label-icon" />
                <span>上传待识别图片</span>
              </div>
              <div class="upload-wrapper">
                <j-image-upload v-model="facePic"></j-image-upload>
              </div>
            </div>

            <!-- 图片预览 -->
            <div class="preview-section" v-if="facePic">
              <div class="preview-header">
                <a-icon type="eye" />
                <span>图片预览</span>
              </div>
              <div class="image-preview-box">
                <img :src="getImageUrl(facePic)" alt="预览图片" class="preview-img" />
                <div class="preview-overlay">
                  <div class="scan-animation"></div>
                  <div class="corner-frame">
                    <span class="corner tl"></span>
                    <span class="corner tr"></span>
                    <span class="corner bl"></span>
                    <span class="corner br"></span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 操作按钮 -->
            <div class="action-section">
              <a-button
                type="primary"
                size="large"
                :loading="recognizing"
                :disabled="!facePic || !modelId"
                @click="handleRecognize"
                block
                class="primary-action-btn"
              >
                <a-icon :type="recognizing ? 'loading' : 'thunderbolt'" />
                <span>{{ recognizing ? '识别分析中...' : '开始识别' }}</span>
              </a-button>
            </div>
          </div>
        </a-col>

        <!-- 右侧：结果展示 -->
        <a-col :span="14" class="right-section">
          <div class="result-panel">
            <!-- 标题区 -->
            <div class="panel-header">
              <div class="header-icon result-icon">
                <a-icon type="bar-chart" />
              </div>
              <div class="header-content">
                <h3 class="panel-title">识别结果</h3>
                <p class="panel-subtitle">
                  AI 分析报告
                  <span class="status-dot" :class="{ active: recognizing }"></span>
                </p>
              </div>
            </div>

            <!-- 结果内容区 -->
            <div class="result-content-wrapper">
              <a-spin :spinning="recognizing" :tip="spinTip" class="custom-spin">
                <!-- 空状态 -->
                <div v-if="!recognitionResult" class="empty-result-state">
                  <div class="empty-animation">
                    <div class="robot-icon">
                      <a-icon type="robot" />
                    </div>
                    <div class="pulse-rings">
                      <span class="ring ring-1"></span>
                      <span class="ring ring-2"></span>
                      <span class="ring ring-3"></span>
                    </div>
                  </div>
                  <h4 class="empty-title">等待识别</h4>
                  <p class="empty-desc">上传图片并选择模型后，点击"开始识别"</p>
                </div>

                <!-- 识别结果 -->
                <div v-else class="result-display">
                  <!-- 状态卡片 -->
                  <div class="status-card" :class="[recognitionResult.success ? 'status-success' : 'status-error']">
                    <div class="status-icon-wrapper">
                      <a-icon :type="recognitionResult.success ? 'check-circle' : 'exclamation-circle'" />
                    </div>
                    <div class="status-info">
                      <h4 class="status-title">{{ recognitionResult.success ? '识别成功' : '识别失败' }}</h4>
                      <p class="status-time">{{ recognitionResult.time }}</p>
                    </div>
                    <div class="status-badge" :class="[recognitionResult.success ? 'badge-success' : 'badge-error']">
                      {{ recognitionResult.success ? 'SUCCESS' : 'FAILED' }}
                    </div>
                  </div>

                  <!-- 识别结果图片展示 -->
                  <div class="face-result-card" v-if="recognitionResult.facePic">
                    <div class="face-image-container">
                      <div class="face-image-wrapper" @click="handlePreviewImage(recognitionResult.facePic)">
                        <img 
                          :src="getImageUrlWithTimestamp(recognitionResult.facePic)" 
                          alt="识别人脸" 
                          class="face-result-img"
                          :key="'result-' + imageTimestamp"
                        />
                        <div class="face-badge">
                          <a-icon type="user" />
                        </div>
                        <div class="image-hover-mask">
                          <a-icon type="eye" class="preview-icon" />
                          <span class="preview-text">点击预览</span>
                        </div>
                      </div>
                    </div>
                    <div class="face-info-container">
                      <div class="face-name-tag">
                        <a-icon type="idcard" />
                        <span>识别结果</span>
                      </div>
                      <div class="face-name-value">{{ recognitionResult.faceName || '未知' }}</div>
                      <div class="face-match-info">
                        <span class="match-label">匹配度</span>
                        <span class="match-value">{{ recognitionResult.confidence }}%</span>
                      </div>
                    </div>
                  </div>

                  <!-- 核心数据 -->
                  <div class="data-cards">
                    <div class="data-card model-card">
                      <div class="card-icon">
                        <a-icon type="deployment-unit" />
                      </div>
                      <div class="card-content">
                        <div class="card-label">识别模型</div>
                        <div class="card-value">{{ recognitionResult.modelName || '未知模型' }}</div>
                      </div>
                    </div>
                    
                    <div class="data-card confidence-card">
                      <div class="card-icon">
                        <a-icon type="dashboard" />
                      </div>
                      <div class="card-content">
                        <div class="card-label">置信度</div>
                        <div class="card-value">{{ recognitionResult.confidence }}%</div>
                        <div class="confidence-bar-wrapper">
                          <div class="confidence-bar">
                            <div 
                              class="confidence-fill" 
                              :style="{ width: recognitionResult.confidence + '%' }"
                            ></div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- 详细结果 -->
                  <div class="details-section">
                    <div class="section-title">
                      <a-icon type="file-text" />
                      <span>识别详情</span>
                      <span class="item-count">{{ recognitionResult.details.length }}</span>
                    </div>
                    
                    <div class="details-list">
                      <div 
                        v-for="(item, index) in recognitionResult.details" 
                        :key="index"
                        class="detail-card"
                        :style="{ animationDelay: `${index * 0.15}s` }"
                      >
                        <div class="detail-info">
                          <div class="detail-tag">{{ item.label }}</div>
                          <div class="detail-result">{{ item.value }}</div>
                        </div>
                        <div class="detail-score">
                          <div class="score-ring">
                            <svg viewBox="0 0 36 36" class="circular-chart">
                              <path class="circle-bg"
                                d="M18 2.0845
                                  a 15.9155 15.9155 0 0 1 0 31.831
                                  a 15.9155 15.9155 0 0 1 0 -31.831"
                              />
                              <path class="circle"
                                :stroke-dasharray="`${item.score}, 100`"
                                d="M18 2.0845
                                  a 15.9155 15.9155 0 0 1 0 31.831
                                  a 15.9155 15.9155 0 0 1 0 -31.831"
                              />
                            </svg>
                            <div class="score-number">{{ item.score }}</div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </a-spin>
            </div>
          </div>
        </a-col>
      </a-row>
    </div>

    <!-- 图片预览Modal -->
    <a-modal
      :visible="previewVisible"
      :footer="null"
      @cancel="handleCancelPreview"
      width="800px"
      centered
      :bodyStyle="{ padding: '20px' }"
    >
      <div class="preview-modal-content">
        <img 
          :src="previewImage" 
          alt="预览图片" 
          class="preview-modal-image"
          :key="'preview-' + imageTimestamp"
        />
      </div>
    </a-modal>
  </div>
</template>

<script>
import {
  httpAction,
  getAction
} from '@/api/manage'

export default {
  name: 'ImageRecognition',
  data() {
    return {
      facePic: "",
      modelId: "",
      recognizing: false,
      recognitionResult: null,
      spinTip: 'AI 正在分析图像...',
      imageTimestamp: Date.now(),
      previewVisible: false,
      previewImage: '',
      url: {
        imgerver: window._CONFIG['domianURL'] || ''
      }
    }
  },
  watch: {
    facePic(newVal) {
      if (!newVal) {
        this.recognitionResult = null
      }
    }
  },
  methods: {
    getImageUrl(path) {
      if (!path) return ''
      if (path.startsWith('http://') || path.startsWith('https://')) {
        return path
      }
      return this.url.imgerver + "/" + path
    },

    // 获取带时间戳的图片URL（强制刷新）
    getImageUrlWithTimestamp(path) {
      const url = this.getImageUrl(path)
      if (!url) return ''
      return url + '?t=' + this.imageTimestamp
    },

    // 预览图片
    handlePreviewImage(imagePath) {
      this.previewImage = this.getImageUrlWithTimestamp(imagePath)
      this.previewVisible = true
    },

    // 关闭预览
    handleCancelPreview() {
      this.previewVisible = false
    },

    async handleRecognize() {
      if (!this.facePic || !this.modelId) {
        this.$message.warning('请上传图片并选择识别模型')
        return
      }

      this.recognizing = true
      
      try {
        let that = this;
        httpAction("/face/tabFacePic/extractFaceFeature", {
          facePic: this.facePic,
          modelId: this.modelId
        }, "POST").then((res) => {
          if (res.success) {
            if (res.result != null) {
              let result = res.result;
              // 更新时间戳，强制刷新图片
              this.imageTimestamp = Date.now();
              
              this.recognitionResult = {
                success: true,
                modelName: `人脸识别模型-${this.modelId}`,
                time: new Date().toLocaleString('zh-CN', { 
                  year: 'numeric', 
                  month: '2-digit', 
                  day: '2-digit',
                  hour: '2-digit', 
                  minute: '2-digit', 
                  second: '2-digit' 
                }),
                confidence: Math.floor(result.maxSimilarity * 100),
                facePic: result.facePic || '',
                faceName: result.faceName || '',
                details: [
                  { 
                    label: '人脸识别', 
                    value: result.faceName, 
                    score: Math.floor(result.maxSimilarity * 100)
                  },
                ]
              }
            } else {
              this.imageTimestamp = Date.now();
              
              this.recognitionResult = {
                success: false,
                modelName: `人脸识别模型-${this.modelId}`,
                time: new Date().toLocaleString('zh-CN', { 
                  year: 'numeric', 
                  month: '2-digit', 
                  day: '2-digit',
                  hour: '2-digit', 
                  minute: '2-digit', 
                  second: '2-digit' 
                }),
                confidence: 0,
                facePic: '',
                faceName: '',
                details: [
                  { label: '人脸识别', value: "未识别到", score: 0 },
                ]
              }
            }
            
            that.recognizing = false
            that.$message.success('识别完成!')
            that.$emit('ok');
          } else {
            that.$message.warning(res.message);
          }
        }).finally(() => {
          that.confirmLoading = false;
        })
        
      } catch (error) {
        this.recognizing = false
        this.$message.error('识别失败: ' + error.message)
      }
    }
  }
}
</script>

<style scoped>
/* ==================== 基础容器 ==================== */
.image-recognition-container {
  position: relative;
  min-height: calc(100vh - 100px);
  max-height: calc(100vh - 100px);
  padding: 16px;
  overflow: hidden;
}

/* ==================== 简洁背景 ==================== */
.clean-background {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(135deg, #f5f9ff 0%, #e6f2ff 100%);
  z-index: 0;
}

.gradient-circle {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  opacity: 0.3;
  animation: float-circle 25s ease-in-out infinite;
}

.circle-1 {
  width: 600px;
  height: 600px;
  background: #1890FF;
  top: -15%;
  right: -15%;
}

.circle-2 {
  width: 500px;
  height: 500px;
  background: #40a9ff;
  bottom: -15%;
  left: -10%;
  animation-delay: 5s;
}

@keyframes float-circle {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(20px, -20px) scale(1.05); }
  66% { transform: translate(-20px, 15px) scale(0.95); }
}

.grid-pattern {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-image: 
    linear-gradient(rgba(24, 144, 255, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(24, 144, 255, 0.03) 1px, transparent 1px);
  background-size: 50px 50px;
  animation: grid-move 30s linear infinite;
}

@keyframes grid-move {
  0% { transform: translate(0, 0); }
  100% { transform: translate(50px, 50px); }
}

/* ==================== 内容包装器 ==================== */
.content-wrapper {
  position: relative;
  z-index: 1;
  max-width: 1400px;
  margin: 0 auto;
  height: calc(100vh - 132px);
}

.main-row {
  height: 100%;
}

/* ==================== 左侧面板 ==================== */
.left-section {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.upload-panel {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 24px;
  box-shadow: 
    0 4px 20px rgba(24, 144, 255, 0.1),
    0 0 0 1px rgba(255, 255, 255, 0.8) inset;
  height: 100%;
  display: flex;
  flex-direction: column;
  transition: all 0.3s ease;
  border: 1px solid rgba(24, 144, 255, 0.1);
}

.upload-panel:hover {
  box-shadow: 
    0 8px 30px rgba(24, 144, 255, 0.15),
    0 0 0 1px rgba(255, 255, 255, 1) inset;
  border-color: rgba(24, 144, 255, 0.2);
}

/* ==================== 面板头部 ==================== */
.panel-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 2px solid rgba(24, 144, 255, 0.1);
  flex-shrink: 0;
}

.header-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: linear-gradient(135deg, #1890FF 0%, #40a9ff 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 24px;
  box-shadow: 0 4px 12px rgba(24, 144, 255, 0.3);
  transition: all 0.3s ease;
}

.header-icon:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(24, 144, 255, 0.4);
}

.result-icon {
  background: linear-gradient(135deg, #1890FF 0%, #40a9ff 100%);
}

.header-content {
  flex: 1;
}

.panel-title {
  font-size: 20px;
  font-weight: 700;
  margin: 0;
  color: #1890FF;
}

.panel-subtitle {
  font-size: 13px;
  color: rgba(0, 0, 0, 0.45);
  margin: 4px 0 0 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.2);
  transition: all 0.3s ease;
}

.status-dot.active {
  background: #52c41a;
  box-shadow: 0 0 10px rgba(82, 196, 26, 0.6);
  animation: pulse-dot 1.5s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.6; transform: scale(1.2); }
}

/* ==================== 表单区域 ==================== */
.form-section {
  margin-bottom: 16px;
  flex-shrink: 0;
}

.form-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
  margin-bottom: 10px;
}

.label-icon {
  color: #1890FF;
  font-size: 16px;
}

.select-wrapper,
.upload-wrapper {
  transition: all 0.3s ease;
}

/* 自定义选择框样式 */
.select-wrapper >>> .ant-select {
  width: 100%;
}

.select-wrapper >>> .ant-select-selection {
  border-radius: 8px;
  border: 2px solid rgba(24, 144, 255, 0.2);
  background: white;
  transition: all 0.3s ease;
}

.select-wrapper >>> .ant-select-selection:hover,
.select-wrapper >>> .ant-select-focused .ant-select-selection {
  border-color: #1890FF;
  box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.1);
}

/* ==================== 图片预览 ==================== */
.preview-section {
  margin-bottom: 16px;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
  margin-bottom: 10px;
  flex-shrink: 0;
}

.preview-header i {
  color: #1890FF;
}

.image-preview-box {
  position: relative;
  width: 100%;
  height: 0;
  flex: 1;
  min-height: 200px;
  max-height: 260px;
  border-radius: 12px;
  overflow: hidden;
  background: #fafafa;
  border: 2px solid rgba(24, 144, 255, 0.2);
  box-shadow: 0 2px 8px rgba(24, 144, 255, 0.08);
  transition: all 0.3s ease;
}

.image-preview-box:hover {
  border-color: #1890FF;
  box-shadow: 0 4px 16px rgba(24, 144, 255, 0.15);
}

.preview-img {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  max-width: 100%;
  max-height: 100%;
  width: auto;
  height: auto;
  object-fit: contain;
  background: white;
}

.preview-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  pointer-events: none;
}

.scan-animation {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, #1890FF, transparent);
  box-shadow: 0 0 15px rgba(24, 144, 255, 0.8);
  animation: scan-move 3s ease-in-out infinite;
}

@keyframes scan-move {
  0% { top: 0; opacity: 0; }
  50% { opacity: 1; }
  100% { top: 100%; opacity: 0; }
}

.corner-frame {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
}

.corner {
  position: absolute;
  width: 24px;
  height: 24px;
  border: 2px solid #1890FF;
  box-shadow: 0 0 8px rgba(24, 144, 255, 0.5);
}

.corner.tl {
  top: 10px;
  left: 10px;
  border-right: none;
  border-bottom: none;
}

.corner.tr {
  top: 10px;
  right: 10px;
  border-left: none;
  border-bottom: none;
}

.corner.bl {
  bottom: 10px;
  left: 10px;
  border-right: none;
  border-top: none;
}

.corner.br {
  bottom: 10px;
  right: 10px;
  border-left: none;
  border-top: none;
}

/* ==================== 操作按钮 ==================== */
.action-section {
  margin-top: auto;
  padding-top: 16px;
  flex-shrink: 0;
}

.primary-action-btn {
  height: 48px;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  background: linear-gradient(135deg, #1890FF 0%, #40a9ff 100%);
  border: none;
  box-shadow: 0 4px 12px rgba(24, 144, 255, 0.3);
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
}

.primary-action-btn::before {
  content: '';
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.3), transparent);
  transition: left 0.5s ease;
}

.primary-action-btn:hover::before {
  left: 100%;
}

.primary-action-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(24, 144, 255, 0.4);
}

.primary-action-btn:disabled {
  background: linear-gradient(135deg, #d9d9d9 0%, #bfbfbf 100%);
  box-shadow: none;
}

.primary-action-btn span {
  position: relative;
  z-index: 1;
}

/* ==================== 右侧面板 ==================== */
.right-section {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.result-panel {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  padding: 24px;
  box-shadow: 
    0 4px 20px rgba(24, 144, 255, 0.1),
    0 0 0 1px rgba(255, 255, 255, 0.8) inset;
  height: 100%;
  display: flex;
  flex-direction: column;
  transition: all 0.3s ease;
  border: 1px solid rgba(24, 144, 255, 0.1);
  overflow: hidden;
}

.result-panel:hover {
  box-shadow: 
    0 8px 30px rgba(24, 144, 255, 0.15),
    0 0 0 1px rgba(255, 255, 255, 1) inset;
  border-color: rgba(24, 144, 255, 0.2);
}

/* ==================== 结果内容区 ==================== */
.result-content-wrapper {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.custom-spin {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.custom-spin >>> .ant-spin-container {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* ==================== 空状态 ==================== */
.empty-result-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
}

.empty-animation {
  position: relative;
  margin-bottom: 24px;
}

.robot-icon {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  background: linear-gradient(135deg, #1890FF 0%, #40a9ff 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 56px;
  box-shadow: 0 8px 24px rgba(24, 144, 255, 0.3);
  animation: float-icon 3s ease-in-out infinite;
  position: relative;
  z-index: 1;
}

@keyframes float-icon {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}

.pulse-rings {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

.ring {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 100px;
  height: 100px;
  border: 2px solid #1890FF;
  border-radius: 50%;
  opacity: 0;
  animation: pulse-ring 2s ease-out infinite;
}

.ring-2 { animation-delay: 0.5s; }
.ring-3 { animation-delay: 1s; }

@keyframes pulse-ring {
  0% {
    transform: translate(-50%, -50%) scale(0.8);
    opacity: 1;
  }
  100% {
    transform: translate(-50%, -50%) scale(1.8);
    opacity: 0;
  }
}

.empty-title {
  font-size: 20px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
  margin: 0 0 10px 0;
}

.empty-desc {
  font-size: 13px;
  color: rgba(0, 0, 0, 0.45);
  margin: 0;
  text-align: center;
  max-width: 280px;
}

/* ==================== 结果显示 ==================== */
.result-display {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 6px;
  min-height: 0;
}

/* 滚动条美化 */
.result-display::-webkit-scrollbar {
  width: 5px;
}

.result-display::-webkit-scrollbar-track {
  background: rgba(24, 144, 255, 0.05);
  border-radius: 3px;
}

.result-display::-webkit-scrollbar-thumb {
  background: rgba(24, 144, 255, 0.3);
  border-radius: 3px;
  transition: all 0.3s ease;
}

.result-display::-webkit-scrollbar-thumb:hover {
  background: rgba(24, 144, 255, 0.5);
}

/* ==================== 状态卡片 ==================== */
.status-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  border-radius: 12px;
  margin-bottom: 16px;
  animation: slide-in 0.5s ease;
  flex-shrink: 0;
}

@keyframes slide-in {
  from {
    opacity: 0;
    transform: translateY(-15px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.status-success {
  background: linear-gradient(135deg, #f6ffed 0%, #d9f7be 100%);
  border: 2px solid #b7eb8f;
}

.status-error {
  background: linear-gradient(135deg, #fff2f0 0%, #ffccc7 100%);
  border: 2px solid #ffa39e;
}

.status-icon-wrapper {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
}

.status-success .status-icon-wrapper {
  background: #52c41a;
  color: white;
  box-shadow: 0 3px 10px rgba(82, 196, 26, 0.3);
}

.status-error .status-icon-wrapper {
  background: #ff4d4f;
  color: white;
  box-shadow: 0 3px 10px rgba(255, 77, 79, 0.3);
}

.status-info {
  flex: 1;
}

.status-title {
  font-size: 16px;
  font-weight: 700;
  margin: 0 0 3px 0;
  color: rgba(0, 0, 0, 0.85);
}

.status-time {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  margin: 0;
}

.status-badge {
  padding: 5px 14px;
  border-radius: 16px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.badge-success {
  background: #52c41a;
  color: white;
}

.badge-error {
  background: #ff4d4f;
  color: white;
}

/* ==================== 识别结果图片卡片 ==================== */
.face-result-card {
  display: flex;
  gap: 16px;
  padding: 16px;
  background: white;
  border: 2px solid rgba(24, 144, 255, 0.15);
  border-radius: 12px;
  margin-bottom: 16px;
  animation: fade-in-up 0.6s ease;
  flex-shrink: 0;
  transition: all 0.3s ease;
}

.face-result-card:hover {
  border-color: #1890FF;
  box-shadow: 0 6px 20px rgba(24, 144, 255, 0.15);
  transform: translateY(-2px);
}

.face-image-container {
  flex-shrink: 0;
}

.face-image-wrapper {
  position: relative;
  width: 100px;
  height: 100px;
  border-radius: 12px;
  overflow: hidden;
  border: 3px solid #1890FF;
  box-shadow: 0 4px 12px rgba(24, 144, 255, 0.2);
  background: #f5f5f5;
  cursor: pointer;
  transition: all 0.3s ease;
}

.face-image-wrapper:hover {
  transform: scale(1.05);
  box-shadow: 0 6px 20px rgba(24, 144, 255, 0.35);
}

.face-image-wrapper:hover .image-hover-mask {
  opacity: 1;
}

.face-result-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.face-badge {
  position: absolute;
  bottom: -2px;
  right: -2px;
  width: 32px;
  height: 32px;
  background: linear-gradient(135deg, #1890FF 0%, #40a9ff 100%);
  border-radius: 8px 0 8px 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 16px;
  box-shadow: 0 2px 8px rgba(24, 144, 255, 0.3);
}

.image-hover-mask {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(24, 144, 255, 0.9);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  opacity: 0;
  transition: opacity 0.3s ease;
  color: white;
}

.preview-icon {
  font-size: 32px;
  animation: pulse-preview 2s ease-in-out infinite;
}

@keyframes pulse-preview {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.1); }
}

.preview-text {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.face-info-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
}

.face-name-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #1890FF;
  font-weight: 600;
}

.face-name-tag i {
  font-size: 14px;
}

.face-name-value {
  font-size: 20px;
  font-weight: 700;
  color: rgba(0, 0, 0, 0.85);
  line-height: 1.2;
}

.face-match-info {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: rgba(24, 144, 255, 0.05);
  border-radius: 8px;
  margin-top: 4px;
}

.match-label {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.match-value {
  font-size: 16px;
  font-weight: 700;
  color: #1890FF;
  margin-left: auto;
}

/* ==================== 数据卡片 ==================== */
.data-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 16px;
  flex-shrink: 0;
}

.data-card {
  padding: 16px;
  border-radius: 12px;
  background: white;
  border: 2px solid rgba(24, 144, 255, 0.1);
  transition: all 0.3s ease;
  animation: fade-in-up 0.6s ease;
  animation-fill-mode: both;
  display: flex;
  gap: 12px;
}

.data-card:nth-child(2) {
  animation-delay: 0.1s;
}

@keyframes fade-in-up {
  from {
    opacity: 0;
    transform: translateY(15px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.data-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 6px 16px rgba(24, 144, 255, 0.15);
  border-color: #1890FF;
}

.card-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  background: linear-gradient(135deg, #1890FF 0%, #40a9ff 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 22px;
  flex-shrink: 0;
  box-shadow: 0 3px 10px rgba(24, 144, 255, 0.3);
}

.card-content {
  flex: 1;
  min-width: 0;
}

.card-label {
  font-size: 11px;
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 5px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
}

.card-value {
  font-size: 18px;
  font-weight: 700;
  color: rgba(0, 0, 0, 0.85);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.confidence-bar-wrapper {
  margin-top: 10px;
}

.confidence-bar {
  height: 6px;
  background: rgba(24, 144, 255, 0.1);
  border-radius: 3px;
  overflow: hidden;
  position: relative;
}

.confidence-fill {
  height: 100%;
  background: linear-gradient(90deg, #1890FF 0%, #40a9ff 100%);
  border-radius: 3px;
  transition: width 1s ease;
  position: relative;
  overflow: hidden;
}

.confidence-fill::after {
  content: '';
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.5), transparent);
  animation: shimmer 2s infinite;
}

@keyframes shimmer {
  0% { left: -100%; }
  100% { left: 100%; }
}

/* ==================== 详情部分 ==================== */
.details-section {
  animation: fade-in-up 0.7s ease 0.2s both;
  flex-shrink: 0;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 700;
  color: rgba(0, 0, 0, 0.85);
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 2px solid rgba(24, 144, 255, 0.1);
}

.section-title i {
  color: #1890FF;
  font-size: 16px;
}

.item-count {
  margin-left: auto;
  padding: 3px 10px;
  background: linear-gradient(135deg, #1890FF 0%, #40a9ff 100%);
  color: white;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 700;
}

.details-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.detail-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px;
  background: white;
  border: 2px solid rgba(24, 144, 255, 0.1);
  border-radius: 10px;
  transition: all 0.3s ease;
  animation: fade-in-up 0.5s ease both;
}

.detail-card:hover {
  transform: translateX(3px);
  border-color: #1890FF;
  box-shadow: 0 3px 12px rgba(24, 144, 255, 0.15);
}

.detail-info {
  flex: 1;
  min-width: 0;
}

.detail-tag {
  display: inline-block;
  padding: 3px 10px;
  background: rgba(24, 144, 255, 0.1);
  color: #1890FF;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 700;
  margin-bottom: 6px;
}

.detail-result {
  font-size: 15px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
}

.detail-score {
  flex-shrink: 0;
  margin-left: 14px;
}

.score-ring {
  position: relative;
  width: 56px;
  height: 56px;
}

.circular-chart {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

.circle-bg {
  fill: none;
  stroke: rgba(24, 144, 255, 0.1);
  stroke-width: 3;
}

.circle {
  fill: none;
  stroke: #1890FF;
  stroke-width: 3;
  stroke-linecap: round;
  animation: draw-circle 1s ease-out forwards;
}

@keyframes draw-circle {
  from { stroke-dasharray: 0, 100; }
}

.score-number {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 15px;
  font-weight: 700;
  color: #1890FF;
}

/* ==================== 自定义 Spin ==================== */
.custom-spin >>> .ant-spin-dot-item {
  background-color: #1890FF;
}

.custom-spin >>> .ant-spin-text {
  color: #1890FF;
  font-weight: 600;
}

/* ==================== 图片预览Modal ==================== */
.preview-modal-content {
  display: flex;
  justify-content: center;
  align-items: center;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
}

.preview-modal-image {
  max-width: 100%;
  max-height: 70vh;
  width: auto;
  height: auto;
  object-fit: contain;
  display: block;
}

/* 自定义Modal样式 */
.ant-modal-content {
  background: rgba(255, 255, 255, 0.98);
  border-radius: 12px;
  overflow: hidden;
}

.ant-modal-close-x {
  width: 48px;
  height: 48px;
  line-height: 48px;
  color: rgba(0, 0, 0, 0.65);
  transition: all 0.3s ease;
}

.ant-modal-close-x:hover {
  color: #1890FF;
}

/* ==================== 响应式 ==================== */
@media (max-width: 1400px) {
  .image-preview-box {
    max-height: 220px;
  }
}

@media (max-width: 1200px) {
  .data-cards {
    grid-template-columns: 1fr;
  }
  
  .face-result-card {
    flex-direction: column;
    align-items: center;
    text-align: center;
  }
  
  .face-match-info {
    justify-content: center;
  }
}

@media (max-width: 768px) {
  .image-recognition-container {
    min-height: auto;
    max-height: none;
    padding: 12px;
  }
  
  .content-wrapper {
    height: auto;
  }
  
  .main-row {
    height: auto;
    flex-direction: column;
  }
  
  .left-section,
  .right-section {
    height: auto;
    margin-bottom: 16px;
  }
  
  .upload-panel,
  .result-panel {
    padding: 20px;
  }
  
  .image-preview-box {
    max-height: 200px;
    min-height: 180px;
  }
}
</style>