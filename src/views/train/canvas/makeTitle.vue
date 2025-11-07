<template>
  <div class="containerOn">
    <!-- 左侧:图片选择列表 -->
    <div class="left-panel">
      <div class="conearchselect" style="width: 100%">
        <j-search-select-tag style="width: 50%" placeholder="请先选择需要标注的图片库" @change="handleSelection"
          v-model="formData.searchValue" :dictOptions="searchOptions">
        </j-search-select-tag>
        <div style="width: 49%; float: right;font-size:20px">
          自动标注进度:{{ autoNum }} : {{autoMarkNum}} <a-button style="float:right;" type="primary"
            @click="autoLabelList">获取数据</a-button>
        </div>
      </div>
      <h3><img src="~@assets/zwyStyle/img/a-8.png" />标注图片列表
        <!-- 在标题左侧放 radio 组 -->
        <a-radio-group v-model="mode" @change="onModeChange" class="mode-radio-group" size="small">
          <a-radio :value="'rect'">矩形标注</a-radio>
          <a-radio :value="'polygon'">多边形标注</a-radio>
          <a-radio :value="'control'">控制点</a-radio>
        </a-radio-group>
        <a-button style="float:right;" type="primary" @click="autoLabel">自动标注</a-button>
      </h3>
      <div class="image-list">
        <div v-for="(image, index) in imageList" :key="index"
          :style="{ backgroundColor: isSelected(image) ? '#6dbe52' : '#d2d2d2' ,height:'22%'} " class="image-item"
          @click="selectImage(image)">
          <img :src="image.src" :alt="image.name" />
          <p style="font-size: 13px;">{{ image.name }}</p>
          <span v-if="isSelected(image)||image.markFlat=='Y'" class="selected-indicator">{{markIcon}}</span>
        </div>
      </div>
      <!-- 分页组件 -->
      <a-pagination :current="currentPage" :total="total" :pageSize="pageSize" @change="handlePageChange"
        style="margin-top: 10px; text-align: center;width: 100%;zoom: 0.8;" />
    </div>

    <!-- 右侧:画布和标注操作区域 -->
    <div class="center-panel">
      <div class="canvas-container">
        <canvas ref="canvas" :width="canvasWidth" :height="canvasHeight" 
        @mousedown="startDraw" 
        @mousemove="drawing"
        @mouseup="endDraw" 
        @click="handleCanvasClick"
        @dblclick="handleCanvasDblClick"
        @contextmenu.prevent="handleRightClick"
        ></canvas>
        <div v-if="mode === 'polygon' && currentPolygon && currentPolygon.points.length > 0" class="polygon-tips">
          <p style="color: #2f51ff; font-weight: bold;">多边形标注模式:</p>
          <p>• 单击添加点 (已添加 {{ currentPolygon.points.length }} 个点)</p>
          <p>• 双击或右键完成绘制</p>
          <p>• 至少需要3个点</p>
        </div>
        <div v-if="mode === 'control'" class="control-tips">
          <p style="color: #2f51ff; font-weight: bold;">控制点标注模式:</p>
          <p>• 单击图像添加控制点</p>
          <p>• 点击右侧列表可删除点</p>
        </div>
      </div>
    </div>
    
    <div class="right-panel">
      <div class="header2">
        <h3 style="float: left;"><img src="~@assets/zwyStyle/img/a-9.png" />标注结果:</h3>
        <br /> <br />
        <a-button style="float:right;color: white;width: 30%;" type="danger" @click="deleteAnnotations">删除图片</a-button>
        <a-button style="float:right;margin-left:1%;margin-right: 1%;width: 30%;" type="primary"
          @click="saveAnnotations">保存标注</a-button>
        <a-button style="float:right;width: 30%;" type="primary" @click="clearAnnotations">清除标记</a-button>
      </div>
      <br /><br />

      <!-- 矩形标注列表 -->
      <div v-if="mode === 'rect' && rectangles.length" style="height: 90%;overflow: auto;">
        <ul>
          <li v-for="(rect, index) in rectangles" :key="'rect-' + index"
            :style="{ backgroundColor: selectedRectIndex === index ? '#c2ffbb' : 'transparent', padding: '5px', cursor: 'pointer' }"
            @click="selectRect(index)">
            <p style="margin-bottom: 0px;">
              <template v-if="editIndex === index">
                <a-input v-model="rect.label" size="small" style="width:120px;" @blur="finishEdit"
                  @pressEnter="finishEdit" />
              </template>
              <template v-else>
                {{ rect.label }}
                <a-button type="link" size="small" @click.stop="editRect(index)">修改</a-button>
              </template>
              <span style="margin: 0 5px;">( {{ rect.width }} x {{ rect.height }} )</span>:
              <a-button type="link" size="small" @click.stop="deleteRect(index)" style="color: red;">删除</a-button>
            </p>
            <p style="color: #0364ff;"><span style="margin-right:30px;">X<span
                  style="margin: 0 4px;">:</span>{{ rect.x }}</span></br><span>Y<span
                  style="margin: 0 4px;">:</span>{{ rect.y }}</span></p>
          </li>
        </ul>
      </div>

      <!-- 多边形标注列表 -->
      <div v-else-if="mode === 'polygon' && polygons.length" style="height: 90%;overflow: auto;">
        <ul>
          <li v-for="(poly, index) in polygons" :key="'poly-' + index"
            :style="{ backgroundColor: selectedPolyIndex === index ? '#c2ffbb' : 'transparent', padding: '5px', cursor: 'pointer' }"
            @click="selectPoly(index)">
            <p style="margin-bottom: 0px;">
              <template v-if="editPolyIndex === index">
                <a-input v-model="poly.label" size="small" style="width:120px;" @blur="finishPolyEdit"
                  @pressEnter="finishPolyEdit" />
              </template>
              <template v-else>
                {{ poly.label }}
                <a-button type="link" size="small" @click.stop="editPoly(index)">修改</a-button>
              </template>
              <span style="margin: 0 5px;">({{ poly.points.length }} 个点)</span>:
              <a-button type="link" size="small" @click.stop="deletePoly(index)" style="color: red;">删除</a-button>
            </p>
            <p style="color: #0364ff; font-size: 12px;">
              点坐标: {{ poly.points.map(p => `(${Math.round(p.x)},${Math.round(p.y)})`).join(' ') }}
            </p>
          </li>
        </ul>
      </div>

      <!-- 控制点列表 -->
      <div v-else-if="mode === 'control' && controlPoints.length" style="height: 90%;overflow: auto;">
        <ul>
          <li v-for="(point, index) in controlPoints" :key="'ctrl-' + index"
            :style="{ backgroundColor: selectedControlIndex === index ? '#c2ffbb' : 'transparent', padding: '5px', cursor: 'pointer' }"
            @click="selectControl(index)">
            <p style="margin-bottom: 0px;">
              <template v-if="editControlIndex === index">
                <a-input v-model="point.label" size="small" style="width:120px;" @blur="finishControlEdit"
                  @pressEnter="finishControlEdit" />
              </template>
              <template v-else>
                {{ point.label || '控制点 ' + (index + 1) }}
                <a-button type="link" size="small" @click.stop="editControl(index)">修改</a-button>
              </template>
              <a-button type="link" size="small" @click.stop="deleteControl(index)" style="color: red;">删除</a-button>
            </p>
            <p style="color: #0364ff;">
              <span>X: {{ Math.round(point.x) }}</span>
              <span style="margin-left: 20px;">Y: {{ Math.round(point.y) }}</span>
            </p>
          </li>
        </ul>
      </div>

      <div v-else>
        <p>{{markText}}</p>
      </div>
    </div>

    <!-- 标签输入弹框 -->
    <a-modal v-model="isModalVisible" title="请输入标注名称" @ok="handleOk" @cancel="handleCancel">
      <a-input ref="inputRef" v-model="currentLabel" placeholder="输入标注名称" @keydown.enter="handleOk" />
      <div v-if="labelHistory.length" style="margin-top: 10px;">
        <a-button type="link" @click="clearAllHistory">清空历史</a-button>
        <span>历史记录:</span>
        <a-tag v-for="item in labelHistory" :key="item" color="blue" closable
          style="cursor: pointer; margin: 4px 4px 0 0;" @click="selectHistory(item)" @close="removeHistory(item)">
          {{ item }}
        </a-tag>
      </div>
    </a-modal>

    <!--自动标注-->
    <a-modal v-model="isAutoModalVisible" title="设置自动标注内容" @ok="handleOkAuto" @cancel="handleCancel" width="600px">
      <a-form-model :model="autoParame" :label-col="{ span: 6 }" :wrapper-col="{ span: 16 }">
        <a-form-model-item label="所属模型">
          <j-search-select-tag v-model="autoParame.aiModel" dict="tab_ai_model,ai_name,id" />
        </a-form-model-item>
        <div style="margin-bottom: 10px; color: red;">
          ( 选择是 → 将从第一张开始标记到最后一张 | 选择否 → 只标记未标记的 )
        </div>
        <a-form-model-item label="是否重新标注">
          <j-dict-select-tag type="list" v-model="autoParame.isMark" dictCode="push_static" placeholder="是否重新标注" />
        </a-form-model-item>
      </a-form-model>
    </a-modal>
  </div>
</template>

<script>
  import JSearchSelectTag from '@/components/dict/JSearchSelectTag'
  import {
    httpAction,
    getAction,
    postAction,
    deleteAction
  } from '@/api/manage'
  import store from '@/store/'
  import Vue from 'vue'
  import {
    ACCESS_TOKEN
  } from '@/store/mutation-types'
  
  export default {
    data() {
      return {
        mode: "rect",
        autoNum: "-",
        autoMarkNum: "-",
        currentPage: 1,
        pageSize: 20,
        total: 0,
        modelid: '',
        modelTry: {},
        autoParame: {
          modelId: '',
          aiModel: '',
          isMark: 1
        },
        formData: {},
        searchOptions: [],
        ImageUrl: "",
        imageList: [{
            name: 'Image 1',
            src: 'image1.jpg',
            id: ""
          },
        ],
        editIndex: null,
        editPolyIndex: null,
        editControlIndex: null,
        markIcon: "✔",
        markText: "暂无标注结果",
        currentImage: null,
        canvasWidth: 700,
        canvasHeight: 700,
        startX: 0,
        startY: 0,
        isDrawing: false,
        hasMoved: false,
        
        // 多边形相关
        currentPolygon: null,
        polygons: [],
        selectedPolyIndex: -1,
        
        // 控制点相关
        controlPoints: [],
        selectedControlIndex: -1,
        
        // 矩形相关
        rectangles: [],
        selectedRectIndex: -1,
        
        label: '',
        currentLabel: '',
        labelHistory: [],
        isModalVisible: false,
        isAutoModalVisible: false,
        image: new Image(),
        selectedImage: null,
        currentAnnotation: null
      };
    },
    watch: {
      isModalVisible(newVal) {
        if (newVal) {
          this.$nextTick(() => {
            this.$refs.inputRef.focus();
          });
        }
      }
    },
    created() {
      this.getModelList();
      const value = this.$route.query.id;
      if (value) {
        this.formData.searchValue = value;
        this.getImageList(value);
      } else {
        this.imageList = [];
      }
    },
    mounted() {
      this.initWebSocket();
      const saved = localStorage.getItem('labelHistory');
      if (saved) {
        this.labelHistory = JSON.parse(saved);
      }
    },
    methods: {
      onModeChange(e) {
        console.log("切换模式:", e.target.value);
        const newMode = e.target.value;
        
        if (newMode !== "rect") {
          if (this.modelTry.modelType == "2") {
            this.mode = "rect";
            this.$message.warning("当前模型只能进行矩形标注!");
            return;
          }
        }
        
        // 切换模式时清除当前正在绘制的内容
        if (this.currentPolygon && this.currentPolygon.points.length > 0) {
          this.$message.info("已取消当前多边形绘制");
          this.currentPolygon = null;
        }
        
        this.isDrawing = false;
        this.drawImage();
      },
      
      finishEdit() {
        this.editIndex = null;
      },
      
      finishPolyEdit() {
        this.editPolyIndex = null;
      },
      
      finishControlEdit() {
        this.editControlIndex = null;
      },
      
      editRect(index) {
        this.editIndex = index;
      },
      
      editPoly(index) {
        this.editPolyIndex = index;
      },
      
      editControl(index) {
        this.editControlIndex = index;
      },
      
      autoLabel() {
        if (this.formData.searchValue) {
          this.isAutoModalVisible = true;
        } else {
          this.$message.warn(`请先选择需要自动标注的库`);
        }
      },
      
      clearAllHistory() {
        this.labelHistory = [];
        localStorage.removeItem('labelHistory');
      },
      
      removeHistory(item) {
        this.labelHistory = this.labelHistory.filter(i => i !== item);
        localStorage.setItem('labelHistory', JSON.stringify(this.labelHistory));
      },
      
      handlePageChange(page) {
        this.currentPage = page;
        this.getImageList(this.formData.searchValue)
      },
      
      autoLabelList() {
        this.getImageList(this.formData.searchValue);
      },
      
      handleSelection(value) {
        this.formData.searchValue = value;
        this.getImageList(value);
        this.getModelInfo(value);
      },
      
      getModelInfo(id) {
        let that = this;
        getAction("/train/tabModelTry/getTabModelTry", {
          id: id,
        }).then((res) => {
          if (res.success) {
            that.modelTry = res.result;
          } else {
            that.$message.warning(res.message);
          }
        })
      },
      
      getImageList(id) {
        if (id == null || id == "") {
          this.$message.warning("请先选择模型库");
          return;
        }

        this.ImageUrl = `${window._CONFIG['domianURL']}/sys/common/static/`;
        let that = this;
        getAction("/train/tabModelTry/listPic", {
          id: id,
          pageNo: that.currentPage,
          pageSize: that.pageSize
        }).then((res) => {
          if (res.success) {
            that.total = res.result.total;
            that.imageList = [];
            let a = 0;
            for (let i of res.result.records) {
              that.imageList.push({
                id: i.id,
                name: i.picName,
                src: that.ImageUrl + i.picUrl,
                markFlat: i.markType,
                markFeature: i.markFeature,
                markJson: i.markJson
              })
              if (a == 0) {
                that.selectImage(that.imageList[a])
              }
              a++;
            }
          } else {
            that.$message.warning(res.message);
          }
        })
      },
      
      getModelList() {
        let that = this;
        getAction("/train/tabModelTry/list", {
          pageNo: 1,
          pageSize: 1000
        }).then((res) => {
          if (res.success) {
            let result = res.result.records;
            if (result) {
              that.searchOptions = [];
              for (let i of result) {
                that.searchOptions.push({
                  text: i.modelName,
                  value: i.id
                })
              }
            }
          } else {
            that.$message.warning(res.message);
          }
        })
      },
      
      isSelected(image) {
        return this.selectedImage === image;
      },
      
      selectRect(index) {
        this.selectedRectIndex = index;
        this.drawImage();
      },
      
      selectPoly(index) {
        this.selectedPolyIndex = index;
        this.drawImage();
      },
      
      selectControl(index) {
        this.selectedControlIndex = index;
        this.drawImage();
      },
      
      deleteRect(index) {
        this.rectangles.splice(index, 1);
        this.selectedRectIndex = -1;
        this.drawImage();
      },
      
      deletePoly(index) {
        this.polygons.splice(index, 1);
        this.selectedPolyIndex = -1;
        this.drawImage();
      },
      
      deleteControl(index) {
        this.controlPoints.splice(index, 1);
        this.selectedControlIndex = -1;
        this.drawImage();
      },
      
      handleCanvasClick(event) {
        if (this.mode === 'polygon') {
          // 多边形模式:添加点
          if (!this.currentImage) return;
          
          const canvas = this.$refs.canvas;
          const rect = canvas.getBoundingClientRect();
          const x = event.clientX - rect.left;
          const y = event.clientY - rect.top;
          
          if (!this.currentPolygon) {
            this.currentPolygon = {
              points: [],
              label: ''
            };
          }
          
          this.currentPolygon.points.push({ x, y });
          this.drawImage();
          
        } else if (this.mode === 'control') {
          // 控制点模式:添加控制点
          if (!this.currentImage) return;
          if (this.isDrawing || this.isModalVisible) return;
          
          const canvas = this.$refs.canvas;
          const rect = canvas.getBoundingClientRect();
          const x = event.clientX - rect.left;
          const y = event.clientY - rect.top;
          
          const newPoint = {
            x,
            y,
            label: '',
            imgwidth: this.image.width,
            imgheight: this.image.height,
            imgId: this.currentImage.id,
            modelId: this.formData.searchValue
          };
          
          this.controlPoints.push(newPoint);
          this.currentAnnotation = newPoint;
          this.showModal(newPoint);
          this.drawImage();
          
        } else if (this.mode === 'rect') {
          // 矩形模式:选中框
          if (this.isDrawing || this.isModalVisible) return;
          
          const canvas = this.$refs.canvas;
          const rect = canvas.getBoundingClientRect();
          const clickX = event.clientX - rect.left;
          const clickY = event.clientY - rect.top;

          let foundIndex = -1;
          for (let i = this.rectangles.length - 1; i >= 0; i--) {
            const r = this.rectangles[i];
            if (clickX >= r.x && clickX <= r.x + r.width &&
              clickY >= r.y && clickY <= r.y + r.height) {
              foundIndex = i;
              break;
            }
          }

          this.selectedRectIndex = foundIndex;
          this.drawImage();
        }
      },
      
      handleCanvasDblClick(event) {
        if (this.mode === 'polygon' && this.currentPolygon && this.currentPolygon.points.length >= 3) {
          this.finishPolygon();
        }
      },
      
      handleRightClick(event) {
        if (this.mode === 'polygon' && this.currentPolygon && this.currentPolygon.points.length >= 3) {
          this.finishPolygon();
        }
      },
      
      finishPolygon() {
        if (!this.currentPolygon || this.currentPolygon.points.length < 3) {
          this.$message.warning("多边形至少需要3个点");
          return;
        }
        
        const poly = {
          points: [...this.currentPolygon.points],
          label: '',
          imgwidth: this.image.width,
          imgheight: this.image.height,
          imgId: this.currentImage.id,
          modelId: this.formData.searchValue
        };
        
        this.polygons.push(poly);
        this.currentAnnotation = poly;
        this.currentPolygon = null;
        this.showModal(poly);
      },
      
      clearAnnotations() {
        if (this.mode === 'rect') {
          this.rectangles = [];
          this.selectedRectIndex = -1;
        } else if (this.mode === 'polygon') {
          this.polygons = [];
          this.currentPolygon = null;
          this.selectedPolyIndex = -1;
        } else if (this.mode === 'control') {
          this.controlPoints = [];
          this.selectedControlIndex = -1;
        }
        this.drawImage();
      },
      
      selectImage(image) {
        console.log("xxxx");
   
        this.currentImage = image;
        this.image.src = image.src;
      
        if (this.currentImage.markFlat == "Y") {
          this.markText = (this.currentImage.markFeature == null ? "标注图" : this.currentImage.markFeature) +
            ":    已完成标注/需要重新标注请重新绘制"
        } else {
          this.markText = "暂无标注";
        }
        
        // 清空所有标注
        this.rectangles = [];
        this.polygons = [];
        this.controlPoints = [];
        this.currentPolygon = null;
        
        // 加载已有标注
        if (this.currentImage.markJson) {
         
          let markJson = JSON.parse(this.currentImage.markJson);
          
          if (markJson && Array.isArray(markJson)) {
            markJson.forEach(item => {
              this.mode=item.type==null?"rect":item.type;
              if (item.type === 'rect' || (!item.type && item.xmin !== undefined)) {
                // 矩形标注
                this.rectangles.push({
                  x: Number(item.xmin) || 0,
                  y: Number(item.ymin) || 0,
                  width: (Number(item.xmax) - Number(item.xmin)) || 0,
                  height: (Number(item.ymax) - Number(item.ymin)) || 0,
                  label: item.name || '',
                  imgwidth: Number(item.ywidth) || Number(this.image.width),
                  imgheight: Number(item.yheight) || Number(this.image.height),
                  imgId: this.currentImage.id,
                  modelId: this.formData.searchValue,
                });
              } else if (item.type === 'polygon' && item.points) {
                // 多边形标注
                this.polygons.push({
                  points: item.points,
                  label: item.name || '',
                  imgwidth: Number(item.ywidth) || Number(this.image.width),
                  imgheight: Number(item.yheight) || Number(this.image.height),
                  imgId: this.currentImage.id,
                  modelId: this.formData.searchValue,
                });
              } else if (item.type === 'control' && item.points) {
                console.log("控制点回显")
                // 控制点标注
                this.controlPoints.push({
                  x: Number(item.points[0].x),
                  y: Number(item.points[0].y),
                  label: item.name || '',
                  imgwidth: Number(item.ywidth) || Number(this.image.width),
                  imgheight: Number(item.yheight) || Number(this.image.height),
                  imgId: this.currentImage.id,
                  modelId: this.formData.searchValue,
                });
              }
            });
          }
        }

        if (this.selectedImage === image) {
          this.selectedImage = null;
        } else {
          this.selectedImage = image;
        }

        this.image.onload = () => {
          this.drawImage();
        };

        this.image.onerror = () => {
          console.error('Image failed to load');
        };
      },
      
      drawImage() {
        if (!this.image.complete) {
          return;
        }

        const canvas = this.$refs.canvas;
        const ctx = canvas.getContext('2d');
        ctx.clearRect(0, 0, this.canvasWidth, this.canvasHeight);
        ctx.drawImage(this.image, 0, 0, this.canvasWidth, this.canvasHeight);

        // 绘制矩形标注
        this.rectangles.forEach((rect, index) => {
          ctx.beginPath();
          ctx.rect(rect.x, rect.y, rect.width, rect.height);
          
          if (index === this.selectedRectIndex) {
            ctx.strokeStyle = '#6dbe52';
            ctx.lineWidth = 4;
            ctx.stroke();
            ctx.fillStyle = 'rgba(109, 190, 82, 0.2)';
            ctx.fillRect(rect.x, rect.y, rect.width, rect.height);
          } else {
            ctx.strokeStyle = 'red';
            ctx.lineWidth = 2;
            ctx.stroke();
          }

          if (rect.label) {
            ctx.fillStyle = index === this.selectedRectIndex ? 'rgba(109, 190, 82, 0.8)' : 'rgba(255, 0, 0, 0.8)';
            ctx.fillRect(rect.x, rect.y - 20, ctx.measureText(rect.label).width + 8, 18);
            ctx.fillStyle = 'white';
            ctx.font = 'bold 12px Arial';
            ctx.fillText(rect.label, rect.x + 4, rect.y - 6);
          }
        });

        // 绘制多边形标注
        this.polygons.forEach((poly, index) => {
          if (poly.points.length < 2) return;
          
          ctx.beginPath();
          ctx.moveTo(poly.points[0].x, poly.points[0].y);
          for (let i = 1; i < poly.points.length; i++) {
            ctx.lineTo(poly.points[i].x, poly.points[i].y);
          }
          ctx.closePath();
          
          if (index === this.selectedPolyIndex) {
            ctx.strokeStyle = '#6dbe52';
            ctx.lineWidth = 4;
            ctx.fillStyle = 'rgba(109, 190, 82, 0.2)';
          } else {
            ctx.strokeStyle = 'blue';
            ctx.lineWidth = 2;
            ctx.fillStyle = 'rgba(0, 0, 255, 0.1)';
          }
          ctx.fill();
          ctx.stroke();
          
          // 绘制顶点
          poly.points.forEach(point => {
            ctx.beginPath();
            ctx.arc(point.x, point.y, 4, 0, 2 * Math.PI);
            ctx.fillStyle = index === this.selectedPolyIndex ? '#6dbe52' : 'blue';
            ctx.fill();
          });
          
          // 绘制标签
          if (poly.label && poly.points.length > 0) {
            const firstPoint = poly.points[0];
            ctx.fillStyle = index === this.selectedPolyIndex ? 'rgba(109, 190, 82, 0.8)' : 'rgba(0, 0, 255, 0.8)';
            ctx.fillRect(firstPoint.x, firstPoint.y - 20, ctx.measureText(poly.label).width + 8, 18);
            ctx.fillStyle = 'white';
            ctx.font = 'bold 12px Arial';
            ctx.fillText(poly.label, firstPoint.x + 4, firstPoint.y - 6);
          }
        });

        // 绘制正在绘制的多边形
        if (this.currentPolygon && this.currentPolygon.points.length > 0) {
          ctx.beginPath();
          ctx.moveTo(this.currentPolygon.points[0].x, this.currentPolygon.points[0].y);
          for (let i = 1; i < this.currentPolygon.points.length; i++) {
            ctx.lineTo(this.currentPolygon.points[i].x, this.currentPolygon.points[i].y);
          }
          ctx.strokeStyle = '#ff9800';
          ctx.lineWidth = 2;
          ctx.setLineDash([5, 5]);
          ctx.stroke();
          ctx.setLineDash([]);
          
          // 绘制顶点
          this.currentPolygon.points.forEach(point => {
            ctx.beginPath();
            ctx.arc(point.x, point.y, 5, 0, 2 * Math.PI);
            ctx.fillStyle = '#ff9800';
            ctx.fill();
          });
        }

        // 绘制控制点
        this.controlPoints.forEach((point, index) => {
          ctx.beginPath();
          ctx.arc(point.x, point.y, 6, 0, 2 * Math.PI);
          
          if (index === this.selectedControlIndex) {
            ctx.fillStyle = '#6dbe52';
            ctx.strokeStyle = '#6dbe52';
            ctx.lineWidth = 3;
          } else {
            ctx.fillStyle = '#ff4d4f';
            ctx.strokeStyle = '#ff4d4f';
            ctx.lineWidth = 2;
          }
          ctx.fill();
          ctx.stroke();
          
          // 绘制十字标记
          ctx.beginPath();
          ctx.moveTo(point.x - 8, point.y);
          ctx.lineTo(point.x + 8, point.y);
          ctx.moveTo(point.x, point.y - 8);
          ctx.lineTo(point.x, point.y + 8);
          ctx.strokeStyle = index === this.selectedControlIndex ? '#6dbe52' : '#ff4d4f';
          ctx.lineWidth = 2;
          ctx.stroke();
          
          // 绘制标签
          if (point.label) {
            ctx.fillStyle = index === this.selectedControlIndex ? 'rgba(109, 190, 82, 0.8)' : 'rgba(255, 77, 79, 0.8)';
            ctx.fillRect(point.x + 10, point.y - 20, ctx.measureText(point.label).width + 8, 18);
            ctx.fillStyle = 'white';
            ctx.font = 'bold 12px Arial';
            ctx.fillText(point.label, point.x + 14, point.y - 6);
          }
        });
      },
      
      startDraw(event) {
        if (!this.currentImage) return;
        
        if (this.mode === 'rect') {
          const canvas = this.$refs.canvas;
          const rect = canvas.getBoundingClientRect();
          this.startX = event.clientX - rect.left;
          this.startY = event.clientY - rect.top;
          this.isDrawing = true;
          this.hasMoved = false;
          this.selectedRectIndex = -1;
        }
      },
      
      drawing(event) {
        if (!this.isDrawing || !this.currentImage || this.mode !== 'rect') return;
        
        this.hasMoved = true;
        const canvas = this.$refs.canvas;
        const ctx = canvas.getContext('2d');
        const rectWidth = event.clientX - canvas.getBoundingClientRect().left - this.startX;
        const rectHeight = event.clientY - canvas.getBoundingClientRect().top - this.startY;

        ctx.clearRect(0, 0, this.canvasWidth, this.canvasHeight);
        this.drawImage();

        ctx.beginPath();
        ctx.rect(this.startX, this.startY, rectWidth, rectHeight);
        ctx.strokeStyle = 'red';
        ctx.lineWidth = 2;
        ctx.stroke();
      },
      
      endDraw(event) {
        if (!this.isDrawing || !this.currentImage || this.mode !== 'rect') return;
        
        if (!this.hasMoved) {
          this.isDrawing = false;
          this.$nextTick(() => {
            this.handleCanvasClick(event);
          });
          return;
        }
        
        const canvas = this.$refs.canvas;
        const rectWidth = event.clientX - canvas.getBoundingClientRect().left - this.startX;
        const rectHeight = event.clientY - canvas.getBoundingClientRect().top - this.startY;
        
        if (Math.abs(rectWidth) > 5 && Math.abs(rectHeight) > 5) {
          const newRect = {
            x: this.startX,
            y: this.startY,
            width: rectWidth,
            height: rectHeight,
            label: '',
            imgwidth: this.image.width,
            imgheight: this.image.height,
            imgId: this.currentImage.id,
            modelId: this.formData.searchValue,
          };
          this.rectangles.push(newRect);
          this.currentAnnotation = newRect;
          this.showModal(newRect);
        }
        
        this.isDrawing = false;
        this.hasMoved = false;
      },
      
      showModal(annotation) {
        this.isModalVisible = true;
        this.currentAnnotation = annotation;
      },
      
      selectHistory(item) {
        this.currentLabel = item;
      },
      
      handleOk() {
        if (this.currentLabel) {
          const trimmed = this.currentLabel.trim();
          if (trimmed && !this.labelHistory.includes(trimmed)) {
            this.labelHistory.push(trimmed);
            localStorage.setItem('labelHistory', JSON.stringify(this.labelHistory));
          }
          this.currentAnnotation.label = this.currentLabel;
          this.drawImage();
          this.isModalVisible = false;
          this.currentLabel = '';
        } else {
          this.$message.warning("当前未标注不可提交");
        }
      },
      
      handleCancel() {
        if (this.mode === 'rect') {
          const index = this.rectangles.indexOf(this.currentAnnotation);
          if (index !== -1) {
            this.rectangles.splice(index, 1);
          }
        } else if (this.mode === 'polygon') {
          const index = this.polygons.indexOf(this.currentAnnotation);
          if (index !== -1) {
            this.polygons.splice(index, 1);
          }
        } else if (this.mode === 'control') {
          const index = this.controlPoints.indexOf(this.currentAnnotation);
          if (index !== -1) {
            this.controlPoints.splice(index, 1);
          }
        }
        
        this.isModalVisible = false;
        this.currentLabel = '';
        this.drawImage();
      },
      
      saveAnnotations() {
        let picXmlList = [];
        
        if (this.mode === 'rect') {
          // 保存矩形标注
          if (this.rectangles.length > 0) {
            for (let i of this.rectangles) {
              picXmlList.push({
                type: 'rect',
                name: i.label,
                xmin: String(Math.round(Number(i.x))),
                xmax: String(Math.round(Number(i.x) + Number(i.width))),
                ymin: String(Math.round(Number(i.y))),
                ymax: String(Math.round(Number(i.y) + Number(i.height))),
                ywidth: Number(i.imgwidth),
                yheight: Number(i.imgheight),
                canvaswidth: Number(this.canvasWidth),
                canvasheight: Number(this.canvasHeight),
                modelId: i.modelId,
                picId: i.imgId,
              })
            }
          }
        } else if (this.mode === 'polygon') {
          // 保存多边形标注
          if (this.polygons.length > 0) {
            for (let poly of this.polygons) {
              // 转换points格式为后端需要的格式
              const points = poly.points.map(p => ({
                x: Number(p.x),
                y: Number(p.y)
              }));
              
              picXmlList.push({
                type: 'polygon',
                name: poly.label,
                points: points,
                ywidth: Number(poly.imgwidth),
                yheight: Number(poly.imgheight),
                canvaswidth: Number(this.canvasWidth),
                canvasheight: Number(this.canvasHeight),
                modelId: poly.modelId,
                picId: poly.imgId,
              })
            }
          }
        } else if (this.mode === 'control') {
          // 保存控制点标注
          if (this.controlPoints.length > 0) {
            for (let point of this.controlPoints) {
              // 控制点转换为points数组格式(包含单个点)
              const points = [{
                x: Number(point.x),
                y: Number(point.y)
              }];
              
              picXmlList.push({
                type: 'control',
                name: point.label,
                points: points,
                ywidth: Number(point.imgwidth),
                yheight: Number(point.imgheight),
                canvaswidth: Number(this.canvasWidth),
                canvasheight: Number(this.canvasHeight),
                modelId: point.modelId,
                picId: point.imgId,
              })
            }
          }
        }

        if (picXmlList.length == 0) {
          if (this.currentImage) {
            let picId = this.currentImage.id;
            if (picId) {
              picXmlList.push({
                picId: picId,
              })
            } else {
              this.$message.warn(`请先选择图片再进行保存`)
              return;
            }
          } else {
            this.$message.warn(`请先选择图片再进行保存`)
            return;
          }
        }
        
        console.log("保存数据:", picXmlList);
        
        postAction("/train/tabModelTry/addMarkPic", picXmlList).then(res => {
          if (res.success) {
            this.$message.success(`保存成功!`)
            this.getImageList(this.formData.searchValue)
          } else {
            this.$message.warn(`保存失败:` + res.message)
          }
        }).finally(() => {})
      },
      
      handleOkAuto() {
        let that = this;
        this.$confirm({
          title: "确认要自动识别吗?",
          content: "自动识别后,当前图片库不可动直到自动识别完成!",
          onOk: function() {
            that.autoParame.modelId = that.formData.searchValue;
            postAction("/train/tabModelTry/autoMarkPic", that.autoParame).then(res => {
              if (res.success) {
                that.$message.success(`开始自动识别!`)
                that.getImageList(that.formData.searchValue)
              } else {
                that.$message.warn(`开始自动识别失败!:` + res.message)
              }
            }).finally(() => {
              that.isAutoModalVisible = false;
            })
          }
        });
      },
      
      deleteAnnotations() {
        let picId = this.currentImage.id;
        let that = this;
        if (picId) {
          this.$confirm({
            title: "确认删除图片吗?",
            content: "图片删除不可恢复!",
            onOk: function() {
              deleteAction("/easy/tabEasyPic/delete", {
                id: picId
              }).then((res) => {
                if (res.success) {
                  that.$message.success(res.message);
                  that.getImageList(that.formData.searchValue)
                } else {
                  that.$message.warning(res.message);
                }
              });
            }
          });
        }
      },
      
      initWebSocket: function() {
        var userId = store.getters.userInfo.id;
        var url = window._CONFIG['domianURL'].replace("https://", "wss://").replace("http://", "ws://") +
          "/websocket/" + userId;
        let token = Vue.ls.get(ACCESS_TOKEN)
        this.websock = new WebSocket(url, [token]);
        this.websock.onopen = this.websocketonopen;
        this.websock.onerror = this.websocketonerror;
        this.websock.onmessage = this.websocketonmessage;
        this.websock.onclose = this.websocketclose;
      },
      
      websocketonopen: function() {
        this.heartCheckFun();
        console.log("WebSocket连接成功");
      },
      
      websocketonerror: function(e) {
        console.log("WebSocket连接发生错误");
      },
      
      websocketonmessage: function(e) {
        var data = eval("(" + e.data + ")");
        if (data.cmd == "auto") {
          if (this.formData.searchValue == data.autoSaveMakeId) {
            this.autoNum = data.autoList;
            this.autoMarkNum = data.autoNumber;
          }
          if (data.autoList == data.autoNumber) {
            this.$message.success(data.autoName + "-自动标注完成");
          }
        }
      },
      
      websocketclose: function(e) {
        console.log("connection closed (" + e.code + ")");
      },
      
      websocketSend(text) {
        try {
          this.websock.send(text);
        } catch (err) {
          console.log("send failed (" + err.code + ")");
        }
      },
      
      heartCheckFun() {
        this.heartbeatInterval = setInterval(() => {
          this.websocketSend("HeartBeat");
        }, 20000);
      },
    },
  };
</script>

<style scoped>
  .containerOn {
    display: flex;
    height: calc(100vh - 145px);
    min-height: 740px;
  }

  .left-panel,
  .center-panel,
  .right-panel {
    box-shadow: 0 0 10px rgba(3, 100, 255, 0.1);
    border-radius: 10px;
    background: linear-gradient(to top, #ffffff, #f5faff) !important;
  }

  .center-panel {
    width: 720px;
    padding: 10px;
    margin-top: 10px;
    margin-bottom: 10px;
  }

  .left-panel {
    width: calc(60% - 380px);
    min-width: 390px;
    margin: 10px;
  }

  .right-panel {
    width: calc(40% - 380px);
    min-width: 280px;
    margin: 10px;
  }

  .left-panel h3 {
    color: #49505b;
    font-size: 14px;
    margin-top: 10px;
  }

  .left-panel h3 img {
    margin-right: 5px;
  }

  .right-panel h3 {
    color: #49505b;
    font-size: 14px;
    line-height: 30px;
  }

  .right-panel h3 img {
    margin-right: 5px;
  }

  .left-panel {
    padding: 10px;
    overflow-y: auto;
    border-right: 1px solid #ddd;
  }

  .image-list {
    display: flex;
    flex-wrap: wrap;
    height: calc(100% - 110px);
    overflow-y: auto;
  }

  .image-item {
    margin: 5px;
    text-align: center;
    cursor: pointer;
    width: calc(20% - 10px);
  }

  .image-item img {
    width: 100%;
    border: 1px solid #ddd;
    margin-bottom: 5px;
  }

  .image-item p {
    margin-bottom: 0;
  }

  .right-panel {
    flex-grow: 1;
    padding: 10px;
  }

  .canvas-container {
    margin-bottom: 20px;
    position: relative;
  }

  canvas {
    cursor: crosshair;
  }

  .polygon-tips,
  .control-tips {
    position: absolute;
    top: 10px;
    left: 10px;
    background: rgba(255, 255, 255, 0.95);
    padding: 10px 15px;
    border-radius: 5px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
    font-size: 13px;
    line-height: 1.6;
  }

  .polygon-tips p,
  .control-tips p {
    margin: 2px 0;
  }

  img {
    object-fit: contain;
  }

  .controls {
    margin-top: 20px;
  }

  .image-item {
    padding: 5px;
    border: 1px solid #ccc;
    margin: 5px;
    position: relative;
    cursor: pointer;
  }

  .selected {
    border: 2px solid #00f;
    background-color: #f0f8ff;
  }

  .selected-indicator {
    position: absolute;
    top: 5px;
    right: 5px;
    color: green;
    font-size: 20px;
  }

  .mode-radio-group {
    margin-left: 20px;
  }
</style>

<style>
  .conearchselect .ant-select-selection {
    background: #eff3f8 !important;
  }

  .containerOn .ant-pagination-item-active a,
  .containerOn .ant-pagination-item-active:focus a {
    color: #fff !important;
    background: #2f51ff;
  }

  .containerOn .ant-pagination-item-active {
    border-color: #2f51ff;
  }

  .containerOn .ant-pagination-item:hover {
    border-color: #2f51ff;
  }

  .containerOn .ant-pagination-item:hover a {
    color: #2f51ff;
  }

  .containerOn .ant-pagination-prev:focus .ant-pagination-item-link,
  .containerOn .ant-pagination-next:focus .ant-pagination-item-link,
  .containerOn .ant-pagination-prev:hover .ant-pagination-item-link,
  .containerOn .ant-pagination-next:hover .ant-pagination-item-link {
    border-color: #2f51ff !important;
    color: #2f51ff !important;
  }

  .containerOn .ant-btn-primary {
    background-color: #2f51ff;
    border-color: #2f51ff;
  }

  .containerOn .ant-btn-danger {
    color: #ff4d4f;
    border-color: #ff4d4f;
  }
</style>