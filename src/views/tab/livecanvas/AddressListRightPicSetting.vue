<template>
  <a-card class="j-address-list-right-card-box conthreelistri" :bordered="false">
    <div class="ceshi" style="width: 100%;height:50px;display: none;"> 
      <input type="text" v-model="url">
      <button @click="geturl()">播放</button>
      <button @click="closeurl()">销毁</button>
    </div>
    
    <div class="video-container">
      <!-- 绘制模式选择按钮 -->
      <div class="draw-mode-buttons">
        <a-button 
          :type="drawMode === 'multi-rect' ? 'primary' : 'default'" 
          @click="setDrawMode('multi-rect')"
          size="small">
          多矩形范围
        </a-button>
        <a-button 
          :type="drawMode === 'multi-polygon' ? 'primary' : 'default'" 
          @click="setDrawMode('multi-polygon')"
          size="small">
          多不规则范围
        </a-button>
        <a-button 
          style="color: white;"
          type="danger" 
          @click="clearCurrentShape"
          size="small"
          v-if="drawMode.includes('polygon') && currentPolygon.length > 0">
          清除当前绘制
        </a-button>
        <a-button 
          style="color: white;"
          type="danger" 
          @click="clearAllShapes"
          size="small"
          v-if="shapes.length > 0">
          清除所有
        </a-button>
        <a-button 
          type="primary" 
          @click="finishPolygon"
          size="small"
          v-if="drawMode.includes('polygon') && currentPolygon.length >= 3">
          完成当前绘制
        </a-button>
      </div>

      <div class="buttons-box" id="buttonsBox">
        <!-- 添加加载提示 -->
        <div v-if="imageLoading" class="image-loading-overlay">
          <a-spin size="large" tip="图片加载中...请稍候" />
        </div>
        <img :src="url" @load="onImgLoad" @error="onImgError" ref="myImg" style="object-fit: contain; width: 100%; height: 100%;">
      </div>
      
      <div id="buttonsText">
        <!-- 使用 a-form-model 包裹，添加验证规则 -->
        <a-form-model ref="formModel" :model="model" :rules="rules">
          <a-col :span="12">
            <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="订阅摄像头" prop="videoId">
              <j-dict-select-tag type="list" :disabled="true" style="width: 100%;" v-model="model.videoId"
                dictCode="tab_ai_subscription_new,name,id" placeholder="请选择订阅摄像头" />
            </a-form-model-item>
          </a-col>
          
          <a-col :span="12">
            <!-- 添加必选标记和验证 -->
            <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="标注范围类型" prop="bzType" required>
              <j-dict-select-tag type="list" style="width: 100%;" v-model="model.bzType"
                dictCode="bz_type" placeholder="请选择标注范围类型" />
            </a-form-model-item>
          </a-col>
          
          <!-- 已绘制区域数量 -->
          <a-col :span="12">
            <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="已绘制区域">
              <a-input :value="shapes.length + '个'" :disabled="true"></a-input>
            </a-form-model-item>
          </a-col>
          
          <!-- 坐标数据查看 -->
          <a-col :span="12">
            <a-form-model-item :labelCol="{span: 10}" :wrapperCol="{span: 12}" label="坐标数据">
              <a-button size="small" @click="showCoordinatesModal" :disabled="shapes.length === 0">查看详情</a-button>
            </a-form-model-item>
          </a-col>

          <!-- 已绘制区域列表 -->
          <a-col :span="24" v-if="shapes.length > 0">
            <a-form-model-item :labelCol="{span: 4}" :wrapperCol="{span: 20}" label="区域列表">
              <div class="shape-list-container">
                <div 
                  v-for="(shape, index) in shapes" 
                  :key="index" 
                  class="shape-item"
                  :class="{ 'shape-item-hover': hoveredShapeIndex === index }"
                  @mouseenter="onShapeHover(index)"
                  @mouseleave="onShapeLeave()">
                  
                  <div class="shape-item-header">
                    <span class="shape-item-title">
                      <a-icon :type="shape.type === 'rect' ? 'border' : 'deployment-unit'" />
                      {{ getShapeTypeName(shape.type) }} {{ index + 1 }}
                    </span>
                    <a-button 
                      style="color: white;" 
                      type="danger" 
                      size="small" 
                      icon="delete"
                      @click="deleteShape(index)">
                      删除
                    </a-button>
                  </div>
                  
                  <div class="shape-item-content">
                    <template v-if="shape.type === 'rect'">
                      <div class="shape-info-row">
                        <span class="shape-info-label">Canvas坐标:</span>
                        <span class="shape-info-value">
                          起点({{ shape.startX.toFixed(0) }}, {{ shape.startY.toFixed(0) }}) 
                          终点({{ shape.endX.toFixed(0) }}, {{ shape.endY.toFixed(0) }})
                        </span>
                      </div>
                      <div class="shape-info-row">
                        <span class="shape-info-label">原图坐标:</span>
                        <span class="shape-info-value">
                          起点({{ shape.originalStartX.toFixed(0) }}, {{ shape.originalStartY.toFixed(0) }}) 
                          终点({{ shape.originalEndX.toFixed(0) }}, {{ shape.originalEndY.toFixed(0) }})
                        </span>
                      </div>
                      <div class="shape-info-row">
                        <span class="shape-info-label">尺寸:</span>
                        <span class="shape-info-value">
                          宽{{ shape.width.toFixed(0) }}px × 高{{ shape.height.toFixed(0) }}px
                        </span>
                      </div>
                    </template>
                    
                    <template v-else>
                      <div class="shape-info-row">
                        <span class="shape-info-label">顶点数:</span>
                        <span class="shape-info-value">{{ shape.points.length }}个</span>
                      </div>
                      <div class="shape-info-row">
                        <span class="shape-info-label">Canvas顶点:</span>
                        <span class="shape-info-value polygon-points">{{ formatPointsSimple(shape.points) }}</span>
                      </div>
                      <div class="shape-info-row">
                        <span class="shape-info-label">原图顶点:</span>
                        <span class="shape-info-value polygon-points">{{ formatPointsSimple(shape.originalPoints) }}</span>
                      </div>
                    </template>
                  </div>
                </div>
              </div>
            </a-form-model-item>
          </a-col>

          <a-col :span="24">
            <a-button type="primary" style="margin: 8px; margin-left: 35%;" @click="edit(null)">重新获取画面</a-button>
            <a-button type="primary" style="margin: 8px;" @click="startRecording">确认配置</a-button>
            <a-button type="danger" style="color: white;" @click="stopRecording">取消配置</a-button>
          </a-col>
        </a-form-model>
      </div>
      
      <canvas 
        ref="canvas" 
        @mousedown="handleMouseDown" 
        @mouseup="handleMouseUp" 
        @mousemove="handleMouseMove"
        @dblclick="handleDoubleClick">
      </canvas>
    </div>
    
    <!-- 坐标详情弹窗 -->
    <a-modal
      title="坐标数据详情"
      :visible="coordinatesModalVisible"
      @cancel="coordinatesModalVisible = false"
      :footer="null"
      width="800px">
      <div style="max-height: 500px; overflow-y: auto;">
        <pre style="background: #f5f5f5; padding: 15px; border-radius: 4px; font-size: 12px;">{{ getCoordinatesJSON() }}</pre>
        <a-button type="primary" @click="copyCoordinates" style="margin-top: 10px;">复制JSON</a-button>
      </div>
    </a-modal>
  </a-card>
</template>

<script>
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  import store from '@/store/'
  import Vue from 'vue'
  import {
    ACCESS_TOKEN
  } from '@/store/mutation-types'

  export default {
    name: 'AddressListRightPic',
    components: {},
    props: ['value'],
    data() {
      return {
        model: {
          id: "1",
          videoId: "",
          bzType: "",  // 注意这里改为 bzType
          shapeData: "",
          shapeCount: 0,
        },
        // ✅ 添加表单验证规则
        rules: {
          bzType: [
            { required: true, message: '请选择标注范围类型', trigger: 'change' }
          ]
        },
        description: '用户信息',
        strokeStyle: 'red',
        fillStyle: 'rgba(255, 0, 0, 0.1)',
        cardLoading: true,
        
        // ✅ 添加图片加载状态
        imageLoading: false,
        imageLoadRetryCount: 0,
        maxRetryCount: 10,
        retryTimer: null,
        
        // 原始图片尺寸
        originalImageWidth: 0,
        originalImageHeight: 0,
        
        // ✅ 图片实际显示的尺寸和位置（考虑object-fit: contain）
        displayImageWidth: 0,
        displayImageHeight: 0,
        displayImageOffsetX: 0,
        displayImageOffsetY: 0,
        
        // Canvas尺寸
        canvasWidth: 0,
        canvasHeight: 0,
        
        // 绘制相关
        drawMode: 'multi-rect',
        drawing: false,
        context: null,
        
        // 存储所有绘制的形状
        shapes: [],
        
        // 当前正在绘制的形状
        currentRect: null,
        currentPolygon: [],
        
        // 鼠标悬停的形状索引
        hoveredShapeIndex: -1,
        
        // 弹窗控制
        coordinatesModalVisible: false,
        
        url: '/logo.png',
        ImageUrl: '',
        pendingShapeData: null,
      }
    },
    
    mounted() {
      this.setupCanvas();
    },
    
    // ✅ 组件销毁时清理定时器
    beforeDestroy() {
      if (this.retryTimer) {
        clearInterval(this.retryTimer);
        this.retryTimer = null;
      }
    },
    
    methods: {
      onImgLoad(e) {
        const img = e.target;
        this.originalImageWidth = img.naturalWidth;
        this.originalImageHeight = img.naturalHeight;
        this.imageLoading = false;
        
        console.log("✅ 图片加载成功 - 原始尺寸:", this.originalImageWidth, "x", this.originalImageHeight);
        
        this.$nextTick(() => {
          this.calculateImageDisplaySize();
          this.setupCanvas();
          
          // ✅ 尝试加载待处理的标注数据
          this.tryLoadPendingData();
        });
      },
      
      // ✅ 添加图片加载错误处理
      onImgError(e) {
        console.error("❌ 图片加载失败");
        this.imageLoading = false;
        this.$message.error('图片加载失败，请重试');
      },
      
      // ✅ 尝试加载待处理的标注数据（带重试机制）
      tryLoadPendingData() {
        if (!this.pendingShapeData) {
          console.log("📍 没有待加载的标注数据");
          return;
        }
        
        // 清除之前的重试定时器
        if (this.retryTimer) {
          clearInterval(this.retryTimer);
          this.retryTimer = null;
        }
        
        this.imageLoadRetryCount = 0;
        
        // 尝试立即加载
        if (this.tryLoadCoordinatesData()) {
          return;
        }
        
        // 如果失败，启动重试机制
        console.log("⏳ 启动标注数据加载重试机制");
        this.retryTimer = setInterval(() => {
          this.imageLoadRetryCount++;
          
          if (this.tryLoadCoordinatesData()) {
            clearInterval(this.retryTimer);
            this.retryTimer = null;
            console.log(`✅ 重试第${this.imageLoadRetryCount}次成功`);
          } else if (this.imageLoadRetryCount >= this.maxRetryCount) {
            clearInterval(this.retryTimer);
            this.retryTimer = null;
            console.error(`❌ 重试${this.maxRetryCount}次后仍然失败`);
            this.$message.warning('标注数据加载失败，请重新获取画面');
          }
        }, 500); // 每500ms重试一次
      },
      
      // ✅ 尝试加载坐标数据（返回是否成功）
      tryLoadCoordinatesData() {
        if (!this.pendingShapeData) {
          return true;
        }
        
        if (this.originalImageWidth === 0 || this.displayImageWidth === 0) {
          console.log(`⏳ 图片尺寸未就绪 (尝试${this.imageLoadRetryCount + 1}/${this.maxRetryCount})`);
          return false;
        }
        
        try {
          console.log("📊 开始加载标注数据");
          this.loadCoordinatesData(this.pendingShapeData);
          this.pendingShapeData = null;
          return true;
        } catch (error) {
          console.error("❌ 加载标注数据出错:", error);
          return false;
        }
      },
      
      // ✅ 计算图片实际显示的尺寸（考虑object-fit: contain）
      calculateImageDisplaySize() {
        const img = this.$refs.myImg;
        const container = document.getElementById('buttonsBox');
        
        if (!img || !container) return;
        
        const containerWidth = container.clientWidth;
        const containerHeight = container.clientHeight;
        const imageAspect = this.originalImageWidth / this.originalImageHeight;
        const containerAspect = containerWidth / containerHeight;
        
        if (imageAspect > containerAspect) {
          // 图片更宽，以宽度为准
          this.displayImageWidth = containerWidth;
          this.displayImageHeight = containerWidth / imageAspect;
          this.displayImageOffsetX = 0;
          this.displayImageOffsetY = (containerHeight - this.displayImageHeight) / 2;
        } else {
          // 图片更高，以高度为准
          this.displayImageHeight = containerHeight;
          this.displayImageWidth = containerHeight * imageAspect;
          this.displayImageOffsetX = (containerWidth - this.displayImageWidth) / 2;
          this.displayImageOffsetY = 0;
        }
        
        console.log("📐 图片显示区域:");
        console.log("  容器尺寸:", containerWidth, "x", containerHeight);
        console.log("  显示尺寸:", this.displayImageWidth.toFixed(0), "x", this.displayImageHeight.toFixed(0));
        console.log("  偏移位置:", this.displayImageOffsetX.toFixed(0), ",", this.displayImageOffsetY.toFixed(0));
      },
      
      setupCanvas() {
        const canvas = this.$refs.canvas;
        const container = document.getElementById('buttonsBox');
        
        if (!canvas || !container) return;
        
        // Canvas 覆盖整个容器
        this.canvasWidth = container.clientWidth;
        this.canvasHeight = container.clientHeight;
        
        canvas.width = this.canvasWidth;
        canvas.height = this.canvasHeight;
        this.context = canvas.getContext("2d");
        
        console.log("🎨 Canvas尺寸:", this.canvasWidth, "x", this.canvasHeight);
        
        if (this.shapes.length > 0) {
          this.redrawShapes();
        }
      },
      
      // ✅ Canvas坐标转换为原图坐标（修复版）
      canvasToOriginal(canvasX, canvasY) {
        if (this.originalImageWidth === 0 || this.displayImageWidth === 0) {
          return { x: canvasX, y: canvasY };
        }
        
        // 减去偏移量，得到相对于图片显示区域的坐标
        const relativeX = canvasX - this.displayImageOffsetX;
        const relativeY = canvasY - this.displayImageOffsetY;
        
        // 转换为原图坐标
        const scaleX = this.originalImageWidth / this.displayImageWidth;
        const scaleY = this.originalImageHeight / this.displayImageHeight;
        
        return {
          x: relativeX * scaleX,
          y: relativeY * scaleY
        };
      },
      
      // ✅ 原图坐标转换为Canvas坐标（修复版）
      originalToCanvas(originalX, originalY) {
        if (this.originalImageWidth === 0 || this.displayImageWidth === 0) {
          return { x: 0, y: 0 };
        }
        
        // 转换为显示区域坐标
        const scaleX = this.displayImageWidth / this.originalImageWidth;
        const scaleY = this.displayImageHeight / this.originalImageHeight;
        
        // 加上偏移量，得到Canvas坐标
        return {
          x: originalX * scaleX + this.displayImageOffsetX,
          y: originalY * scaleY + this.displayImageOffsetY
        };
      },
      
      // ========== 删除功能 ==========
      deleteShape(index) {
        this.$confirm({
          title: '确认删除',
          content: `确定要删除 ${this.getShapeTypeName(this.shapes[index].type)} ${index + 1} 吗？`,
          onOk: () => {
            this.shapes.splice(index, 1);
            this.redrawShapes();
            this.$message.success('删除成功');
          }
        });
      },
      
      // ========== 鼠标悬停高亮 ==========
      onShapeHover(index) {
        this.hoveredShapeIndex = index;
        this.redrawShapes();
      },
      
      onShapeLeave() {
        this.hoveredShapeIndex = -1;
        this.redrawShapes();
      },
      
      // ========== 设置绘制模式 ==========
      setDrawMode(mode) {
        if (this.drawMode.includes('polygon') && this.currentPolygon.length > 0) {
          this.$confirm({
            title: '切换模式',
            content: '当前有未完成的多边形绘制，切换模式将清除未完成的绘制，是否继续？',
            onOk: () => {
              this.currentPolygon = [];
              this.drawMode = mode;
              this.redrawShapes();
            }
          });
        } else {
          this.drawMode = mode;
          this.currentRect = null;
          this.currentPolygon = [];
          this.redrawShapes();
        }
      },
      
      // ========== 鼠标事件 ==========
      handleMouseDown(event) {
        if (this.drawMode.includes('rect')) {
          this.startDrawingRect(event);
        }
      },
      
      handleMouseUp(event) {
        if (this.drawMode.includes('rect')) {
          this.stopDrawingRect(event);
        }
      },
      
      handleMouseMove(event) {
        if (this.drawMode.includes('rect')) {
          this.drawRect(event);
        } else if (this.drawMode.includes('polygon')) {
          this.drawPolygonPreview(event);
        }
      },
      
      handleDoubleClick(event) {
        if (this.drawMode.includes('polygon')) {
          this.addPolygonPoint(event);
        }
      },
      
      // ========== 矩形绘制 ==========
      startDrawingRect(event) {
        this.drawing = true;
        this.currentRect = {
          type: 'rect',
          startX: event.offsetX,
          startY: event.offsetY,
          endX: event.offsetX,
          endY: event.offsetY,
        };
      },
      
      stopDrawingRect() {
        if (!this.drawing || !this.currentRect) return;
        
        this.drawing = false;
        
        const minX = Math.min(this.currentRect.startX, this.currentRect.endX);
        const minY = Math.min(this.currentRect.startY, this.currentRect.endY);
        const maxX = Math.max(this.currentRect.startX, this.currentRect.endX);
        const maxY = Math.max(this.currentRect.startY, this.currentRect.endY);
        
        // 检查是否太小
        if ((maxX - minX) < 5 || (maxY - minY) < 5) {
          this.currentRect = null;
          this.redrawShapes();
          return;
        }
        
        const originalStart = this.canvasToOriginal(minX, minY);
        const originalEnd = this.canvasToOriginal(maxX, maxY);
        
        const rect = {
          type: 'rect',
          startX: minX,
          startY: minY,
          endX: maxX,
          endY: maxY,
          originalStartX: originalStart.x,
          originalStartY: originalStart.y,
          originalEndX: originalEnd.x,
          originalEndY: originalEnd.y,
          width: originalEnd.x - originalStart.x,
          height: originalEnd.y - originalStart.y
        };
        
        this.shapes.push(rect);
        this.currentRect = null;
        
        this.redrawShapes();
        console.log("✅ 保存矩形 - Canvas:", minX.toFixed(0), minY.toFixed(0), "→", maxX.toFixed(0), maxY.toFixed(0));
        console.log("   原图:", originalStart.x.toFixed(0), originalStart.y.toFixed(0), "→", originalEnd.x.toFixed(0), originalEnd.y.toFixed(0));
      },
      
      drawRect(event) {
        if (!this.drawing || !this.currentRect) return;

        const x = event.offsetX;
        const y = event.offsetY;

        this.currentRect.endX = x;
        this.currentRect.endY = y;

        this.redrawShapes();
        
        this.context.strokeStyle = this.strokeStyle;
        this.context.lineWidth = 2;
        this.context.strokeRect(
          this.currentRect.startX,
          this.currentRect.startY,
          x - this.currentRect.startX,
          y - this.currentRect.startY
        );
      },
      
      // ========== 多边形绘制 ==========
      addPolygonPoint(event) {
        const point = {
          x: event.offsetX,
          y: event.offsetY
        };
        
        this.currentPolygon.push(point);
        this.redrawShapes();
      },
      
      drawPolygonPreview(event) {
        if (!this.drawMode.includes('polygon') || this.currentPolygon.length === 0) return;
        
        this.redrawShapes();
        
        this.context.strokeStyle = 'rgba(255, 0, 0, 0.5)';
        this.context.lineWidth = 1;
        this.context.setLineDash([5, 5]);
        this.context.beginPath();
        const lastPoint = this.currentPolygon[this.currentPolygon.length - 1];
        this.context.moveTo(lastPoint.x, lastPoint.y);
        this.context.lineTo(event.offsetX, event.offsetY);
        this.context.stroke();
        this.context.setLineDash([]);
      },
      
      finishPolygon() {
        if (this.currentPolygon.length < 3) {
          this.$message.warning('至少需要3个点才能形成多边形');
          return;
        }
        
        const originalPoints = this.currentPolygon.map(point => {
          const original = this.canvasToOriginal(point.x, point.y);
          return {
            x: original.x,
            y: original.y
          };
        });
        
        const polygon = {
          type: 'polygon',
          points: [...this.currentPolygon],
          originalPoints: originalPoints
        };
        
        this.shapes.push(polygon);
        this.currentPolygon = [];
        this.redrawShapes();
        
        console.log("✅ 保存多边形 - 顶点数:", polygon.points.length);
        this.$message.success('多边形绘制完成');
      },
      
      // ========== 绘制方法 ==========
      redrawShapes() {
        if (!this.context) return;
        
        this.context.clearRect(0, 0, this.$refs.canvas.width, this.$refs.canvas.height);
        
        this.shapes.forEach((shape, index) => {
          const isHovered = index === this.hoveredShapeIndex;
          if (shape.type === 'rect') {
            this.drawSavedRect(shape, index, isHovered);
          } else if (shape.type === 'polygon') {
            this.drawSavedPolygon(shape, index, isHovered);
          }
        });
        
        if (this.currentPolygon.length > 0) {
          this.drawCurrentPolygon();
        }
      },
      
      drawSavedRect(rect, index, isHovered = false) {
        this.context.strokeStyle = isHovered ? '#ff4d4f' : this.strokeStyle;
        this.context.fillStyle = isHovered ? 'rgba(255, 77, 79, 0.2)' : this.fillStyle;
        this.context.lineWidth = isHovered ? 3 : 2;
        
        const width = rect.endX - rect.startX;
        const height = rect.endY - rect.startY;
        
        this.context.fillRect(rect.startX, rect.startY, width, height);
        this.context.strokeRect(rect.startX, rect.startY, width, height);
        
        this.context.fillStyle = isHovered ? '#ff4d4f' : 'red';
        this.context.font = isHovered ? 'bold 16px Arial' : '14px Arial';
        this.context.fillText(`矩形${index + 1}`, rect.startX + 5, rect.startY + 20);
      },
      
      drawSavedPolygon(polygon, index, isHovered = false) {
        if (polygon.points.length < 3) return;
        
        this.context.strokeStyle = isHovered ? '#ff4d4f' : this.strokeStyle;
        this.context.fillStyle = isHovered ? 'rgba(255, 77, 79, 0.2)' : this.fillStyle;
        this.context.lineWidth = isHovered ? 3 : 2;
        
        this.context.beginPath();
        this.context.moveTo(polygon.points[0].x, polygon.points[0].y);
        
        for (let i = 1; i < polygon.points.length; i++) {
          this.context.lineTo(polygon.points[i].x, polygon.points[i].y);
        }
        
        this.context.closePath();
        this.context.fill();
        this.context.stroke();
        
        polygon.points.forEach((point) => {
          this.context.fillStyle = isHovered ? '#ff4d4f' : 'red';
          this.context.beginPath();
          this.context.arc(point.x, point.y, isHovered ? 5 : 4, 0, 2 * Math.PI);
          this.context.fill();
        });
        
        this.context.fillStyle = isHovered ? '#ff4d4f' : 'red';
        this.context.font = isHovered ? 'bold 16px Arial' : '14px Arial';
        this.context.fillText(`不规则${index + 1}`, polygon.points[0].x + 5, polygon.points[0].y - 10);
      },
      
      drawCurrentPolygon() {
        if (this.currentPolygon.length === 0) return;
        
        this.context.strokeStyle = 'rgba(255, 0, 0, 0.8)';
        this.context.fillStyle = 'rgba(255, 0, 0, 0.1)';
        this.context.lineWidth = 2;
        
        this.context.beginPath();
        this.context.moveTo(this.currentPolygon[0].x, this.currentPolygon[0].y);
        
        for (let i = 1; i < this.currentPolygon.length; i++) {
          this.context.lineTo(this.currentPolygon[i].x, this.currentPolygon[i].y);
        }
        
        if (this.currentPolygon.length >= 3) {
          this.context.closePath();
          this.context.fill();
        }
        this.context.stroke();
        
        this.currentPolygon.forEach((point, index) => {
          this.context.fillStyle = 'blue';
          this.context.beginPath();
          this.context.arc(point.x, point.y, 5, 0, 2 * Math.PI);
          this.context.fill();
          
          this.context.fillStyle = 'white';
          this.context.font = '10px Arial';
          this.context.fillText(index + 1, point.x - 3, point.y + 3);
        });
      },
      
      // ========== 清除功能 ==========
      clearCurrentShape() {
        this.currentPolygon = [];
        this.redrawShapes();
        this.$message.success('已清除当前未完成的绘制');
      },
      
      clearAllShapes() {
        this.$confirm({
          title: '确认清除',
          content: '确定要清除所有绘制的形状吗？',
          onOk: () => {
            this.shapes = [];
            this.currentPolygon = [];
            this.currentRect = null;
            this.redrawShapes();
            this.$message.success('已清除所有形状');
          }
        });
      },
      
      clearDrawing() {
        if (!this.context) return;
        this.context.clearRect(0, 0, this.$refs.canvas.width, this.$refs.canvas.height);
        this.shapes = [];
        this.currentPolygon = [];
        this.currentRect = null;
      },
      
      // ========== 数据保存 ==========
      getCoordinatesData() {
        const data = {
          version: "1.0",
          imageWidth: this.originalImageWidth,
          imageHeight: this.originalImageHeight,
          drawMode: this.drawMode,
          shapeCount: this.shapes.length,
          shapes: this.shapes.map((shape, index) => {
            if (shape.type === 'rect') {
              return {
                id: index,
                type: 'rect',
                coordinates: {
                  startX: shape.originalStartX,
                  startY: shape.originalStartY,
                  endX: shape.originalEndX,
                  endY: shape.originalEndY,
                  width: shape.width,
                  height: shape.height
                }
              };
            } else {
              return {
                id: index,
                type: 'polygon',
                coordinates: {
                  points: shape.originalPoints
                }
              };
            }
          })
        };
        
        return data;
      },
      
      loadCoordinatesData(jsonData) {
        try {
          const data = typeof jsonData === 'string' ? JSON.parse(jsonData) : jsonData;
          
          if (!data || !data.shapes) {
            console.warn("没有有效的坐标数据");
            return;
          }
          
          if (this.originalImageWidth === 0 || this.displayImageWidth === 0) {
            console.warn("⏳ 图片尺寸未初始化");
            throw new Error("图片尺寸未初始化");
          }
          
          console.log("📊 加载坐标数据 - 形状数量:", data.shapes.length);
          
          this.shapes = [];
          
          data.shapes.forEach((shapeData, idx) => {
            if (shapeData.type === 'rect') {
              const coords = shapeData.coordinates;
              
              const canvasStart = this.originalToCanvas(coords.startX, coords.startY);
              const canvasEnd = this.originalToCanvas(coords.endX, coords.endY);
              
              console.log(`  矩形${idx + 1}: 原图(${coords.startX.toFixed(0)},${coords.startY.toFixed(0)}) → Canvas(${canvasStart.x.toFixed(0)},${canvasStart.y.toFixed(0)})`);
              
              this.shapes.push({
                type: 'rect',
                startX: canvasStart.x,
                startY: canvasStart.y,
                endX: canvasEnd.x,
                endY: canvasEnd.y,
                originalStartX: coords.startX,
                originalStartY: coords.startY,
                originalEndX: coords.endX,
                originalEndY: coords.endY,
                width: coords.width,
                height: coords.height
              });
            } else if (shapeData.type === 'polygon') {
              const originalPoints = shapeData.coordinates.points;
              
              const canvasPoints = originalPoints.map(point => {
                const canvas = this.originalToCanvas(point.x, point.y);
                return { x: canvas.x, y: canvas.y };
              });
              
              console.log(`  多边形${idx + 1}: ${originalPoints.length}个顶点`);
              
              this.shapes.push({
                type: 'polygon',
                points: canvasPoints,
                originalPoints: originalPoints
              });
            }
          });
          
          this.redrawShapes();
          console.log("✅ 坐标数据加载完成");
        } catch (error) {
          console.error("❌ 加载坐标数据失败:", error);
          throw error;
        }
      },
      
      edit(record) {
        // ✅ 清除之前的定时器
        if (this.retryTimer) {
          clearInterval(this.retryTimer);
          this.retryTimer = null;
        }
        
        this.ImageUrl = `${window._CONFIG['domianURL']}/sys/common/static/`;
        if (record != null) {
          this.model.videoId = record.subId;
          this.model.id = record.id;
        }
        
        let that = this;
        
        // 清空数据
        this.shapes = [];
        this.pendingShapeData = null;
        this.originalImageWidth = 0;
        this.originalImageHeight = 0;
        this.displayImageWidth = 0;
        this.displayImageHeight = 0;
        this.imageLoadRetryCount = 0;
        this.imageLoading = true;  // ✅ 设置加载状态
        
        console.log("🔄 开始加载配置");
        
        getAction("/video/tabVideoUtil/getVideoPicSetting", {
          id: this.model.id
        }).then((res) => {
          if (res.success) {
            this.model = res.result;
            
            if (this.model.shapeData) {
              console.log("📥 获取到坐标数据，等待图片加载");
              this.pendingShapeData = this.model.shapeData;
            }
            
            that.$message.success("获取配置成功！");
          } else {
            that.$message.warning("未获取到配置");
          }
          
          getAction("/video/tabAiSubscriptionNew/getVideoPic", {
            id: this.model.videoId
          }).then((res) => {
            if (res.success) {
              that.url = this.ImageUrl + res.result;
              console.log("🖼️ 开始加载图片");
              
              // ✅ 如果图片已经在缓存中，可能不会触发load事件，手动触发
              this.$nextTick(() => {
                const img = this.$refs.myImg;
                if (img && img.complete && img.naturalWidth > 0) {
                  console.log("📸 图片已缓存，手动触发加载");
                  this.onImgLoad({ target: img });
                }
              });
            } else {
              that.$message.warning("获取图片失败");
              that.imageLoading = false;
            }
          }).catch(() => {
            that.imageLoading = false;
          });
        }).catch(() => {
          that.imageLoading = false;
        });
      },
      
      startRecording() {
        let that = this;
        
        // ✅ 先进行表单验证
        this.$refs.formModel.validate(valid => {
          if (!valid) {
            this.$message.warning('请完善必填信息');
            return;
          }
          
          if (this.shapes.length === 0) {
            this.$message.warning('请先绘制区域范围');
            return;
          }
          
          const coordinatesData = this.getCoordinatesData();
          
          this.model.shapeData = JSON.stringify(coordinatesData);
          this.model.shapeCount = this.shapes.length;
          
          console.log("💾 准备保存 -", this.shapes.length, "个区域");
          
          this.$confirm({
            title: "确认提交修改配置吗",
            content: `将保存 ${this.shapes.length} 个区域的坐标信息`,
            onOk: function() {
              let httpurl = '/video/tabVideoUtil/saveBoxSetting';
              let method = 'post';

              httpAction(httpurl, that.model, method).then((res) => {
                if (res.success) {
                  that.$message.success(res.message);
                  that.$emit('ok');
                } else {
                  that.$message.warning(res.message);
                }
              }).finally(() => {
                that.confirmLoading = false;
              })
            }
          });
        });
      },
      
      stopRecording() {
        const that = this;
        this.$confirm({
          title: "确认取消配置吗？",
          content: "确认取消配置吗!",
          onOk: function() {
            that.clearDrawing();
            that.$message.success("取消成功");
          }
        });
      },
      
      // ========== 辅助方法 ==========
      getShapeTypeName(type) {
        return type === 'rect' ? '矩形' : '不规则';
      },
      
      formatPointsSimple(points) {
        if (!points || points.length === 0) return '';
        if (points.length <= 3) {
          return points.map(p => `(${p.x.toFixed(0)},${p.y.toFixed(0)})`).join(' ');
        }
        return `${points.length}个顶点`;
      },
      
      showCoordinatesModal() {
        this.coordinatesModalVisible = true;
      },
      
      getCoordinatesJSON() {
        return JSON.stringify(this.getCoordinatesData(), null, 2);
      },
      
      copyCoordinates() {
        const json = this.getCoordinatesJSON();
        navigator.clipboard.writeText(json).then(() => {
          this.$message.success('已复制到剪贴板');
        }).catch(() => {
          this.$message.error('复制失败');
        });
      },
    }
  }
</script>

<style scoped>
  .ant-card-body {
    height: 100%;
    min-height: 740px;
    padding: 10px;
    box-sizing: border-box;
  }
  
  /* ✅ 添加图片加载提示样式 */
  .image-loading-overlay {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    background: rgba(255, 255, 255, 0.9);
    display: flex;
    justify-content: center;
    align-items: center;
    z-index: 5;
  }
  
  .draw-mode-buttons {
    position: absolute;
    top: 10px;
    left: 10px;
    z-index: 10;
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    max-width: 620px;
  }
  
  .draw-mode-buttons .ant-btn {
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  }

  #buttonsBox {
    height: 640px;
    width: 640px;
    min-height: 400px;
    float: left;
    position: relative;
  }

  #buttonsText {
    float: left;
    width: calc(100% - 640px)
  }

  .video-container {
    position: relative;
  }

  canvas {
    position: absolute;
    top: 0;
    left: 0;
    cursor: crosshair;
  }
  
  /* 形状列表样式 */
  .shape-list-container {
    max-height: 400px;
    overflow-y: auto;
    border: 1px solid #d9d9d9;
    border-radius: 4px;
    padding: 8px;
    background-color: #fafafa;
  }
  
  .shape-item {
    background: white;
    border: 1px solid #e8e8e8;
    border-radius: 4px;
    padding: 12px;
    margin-bottom: 8px;
    transition: all 0.3s ease;
  }
  
  .shape-item:last-child {
    margin-bottom: 0;
  }
  
  .shape-item:hover {
    border-color: #40a9ff;
    box-shadow: 0 2px 8px rgba(24, 144, 255, 0.2);
  }
  
  .shape-item-hover {
    border-color: #ff4d4f !important;
    box-shadow: 0 2px 8px rgba(255, 77, 79, 0.3) !important;
    background-color: #fff1f0 !important;
  }
  
  .shape-item-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;
    padding-bottom: 8px;
    border-bottom: 1px solid #f0f0f0;
  }
  
  .shape-item-title {
    font-weight: 600;
    font-size: 14px;
    color: #333;
  }
  
  .shape-item-title .anticon {
    margin-right: 6px;
    color: #1890ff;
  }
  
  .shape-item-content {
    font-size: 12px;
  }
  
  .shape-info-row {
    display: flex;
    margin-bottom: 4px;
    line-height: 1.8;
  }
  
  .shape-info-row:last-child {
    margin-bottom: 0;
  }
  
  .shape-info-label {
    color: #666;
    min-width: 80px;
    font-weight: 500;
  }
  
  .shape-info-value {
    color: #0364ff;
    flex: 1;
    word-break: break-all;
  }
  
  .polygon-points {
    font-family: 'Courier New', monospace;
    font-size: 11px;
  }
  
  /* 滚动条样式 */
  .shape-list-container::-webkit-scrollbar {
    width: 6px;
  }
  
  .shape-list-container::-webkit-scrollbar-thumb {
    background-color: #bfbfbf;
    border-radius: 3px;
  }
  
  .shape-list-container::-webkit-scrollbar-thumb:hover {
    background-color: #999;
  }
  
  .shape-list-container::-webkit-scrollbar-track {
    background-color: #f0f0f0;
    border-radius: 3px;
  }
</style>

<style>
  .conthreelistri {
    margin: 10px 0;
    box-shadow: 0 0 10px rgba(3, 100, 255, 0.1);
    border-radius: 10px;
    background: linear-gradient(to top, #ffffff, #f5faff) !important;
    height: calc(100vh - 173px);
    min-height: 740px !important;
    border-radius: 10px !important;
    overflow: hidden;
  }

  .conthreelistri .ant-card-body {
    padding: 10px;
    height: 100%;
    box-sizing: border-box;
  }

  .conthreelistri #buttonsText .ant-form-item-label {
    width: 120px;
    float: left;
  }

  .conthreelistri #buttonsText .ant-form-item-control-wrapper {
    width: calc(100% - 120px);
    float: left;
  }

  .conthreelistri #buttonsText .ant-select-selection {
    background-color: #f5f5f5;
  }

  .conthreelistri .ant-btn-primary {
    background-color: #2f51ff;
    border-color: #2f51ff;
  }

  .conthreelistri .ant-btn-danger {
    color: #ff4d4f;
    border-color: #ff4d4f;
  }
</style>