<template>
  <div class="containerOn">
    <!-- 左侧：图片选择列表 -->
    <div class="left-panel">
      <div class="conearchselect" style="width: 100%">
        <j-search-select-tag style="width: 50%" placeholder="请先选择需要标注的图片库" @change="handleSelection"
          v-model="formData.searchValue" :dictOptions="searchOptions">
        </j-search-select-tag>
        <div style="width: 49%; float: right;font-size:20px">
          自动标注进度：{{ autoNum }} : {{autoMarkNum}} <a-button style="float:right;" type="primary"
            @click="autoLabelList">获取数据</a-button>
        </div>
      </div>
      <h3><img src="~@assets/zwyStyle/img/a-8.png" />标注图片列表
        <!-- 在标题左侧放 radio 组（也可以改为右侧） -->
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

    <!-- 右侧：画布和标注操作区域 -->
    <div class="center-panel">
      <div class="canvas-container">
        <canvas ref="canvas" :width="canvasWidth" :height="canvasHeight" 
        @mousedown="startDraw" 
        @mousemove="drawing"
        @mouseup="endDraw" 
        @click="handleCanvasClick"
        
        ></canvas>
      </div>

    </div>
    <div class="right-panel">
      <div class="header2">
        <h3 style="float: left;"><img src="~@assets/zwyStyle/img/a-9.png" />标注结果:</h3>
        <!-- 保存按钮居右 -->
        <br /> <br />
        <a-button style="float:right;color: white;width: 30%;" type="danger" @click="deleteAnnotations">删除图片</a-button>

        <a-button style="float:right;margin-left:1%;margin-right: 1%;width: 30%;" type="primary"
          @click="saveAnnotations">保存标注</a-button>
        <a-button style="float:right;width: 30%;" type="primary" @click="clearAnnotations">清除标记</a-button>
      </div>
      <br /><br />

      <div v-if="rectangles.length" style="height: 90%;overflow: auto;">
        <ul>
          <li v-for="(rect, index) in rectangles" :key="index"
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


              <span style="margin: 0 5px;">( {{ rect.width }} x
                {{ rect.height }} )</span>:
              <a-button type="link" size="small" @click.stop="deleteRect(index)" style="color: red;">删除</a-button>
            </p>
            <p style="color: #0364ff;"><span style="margin-right:30px;">X<span
                  style="margin: 0 4px;">:</span>{{ rect.x }}</span></br><span>Y<span
                  style="margin: 0 4px;">:</span>{{ rect.y }}</span></p>
          </li>
        </ul>
      </div>
      <div v-else>
        <p>{{markText}}</p>
      </div>
    </div>

    <!-- 使用 Ant Design Vue 的 Modal 弹框输入标签 -->
    <a-modal v-model="isModalVisible" title="请输入标注名称" @ok="handleOk" @cancel="handleCancel">
      <a-input ref="inputRef" v-model="currentLabel" placeholder="输入标注名称" @keydown.enter="handleOk" />
      <div v-if="labelHistory.length" style="margin-top: 10px;">
        <a-button type="link" @click="clearAllHistory">清空历史</a-button>
        <span>历史记录：</span>
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
          （ 选择是 → 将从第一张开始标记到最后一张 | 选择否 → 只标记未标记的 ）
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
  import {
    forOf
  } from 'xe-utils/methods'
  export default {
    data() {
      return {
        mode: "rect",
        autoNum: "-",
        autoMarkNum: "-",
        currentPage: 1,
        pageSize: 20, // 每页显示 8 张图片
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
        markIcon: "✔",
        markText: "暂无标注结果",
        currentImage: null,
        canvasWidth: 700,
        canvasHeight: 700,
        startX: 0,
        startY: 0,
        isDrawing: false,
        hasMoved: false, // 新增：标记鼠标是否移动过
        currentPolygon: null, // 临时存放正在绘制的多边形
        polygons: [], // 多边形集合
        controlPoints: [], // 控制点集合
        rectangles: [],
        selectedRectIndex: -1, // 新增：当前选中的框索引
        label: '',
        currentLabel: '', // 当前输入的标签
        labelHistory: [],
        isModalVisible: false, // 控制 Modal 显示与隐藏
        isAutoModalVisible: false, // 控制 Modal自动标注 显示与隐藏
        image: new Image(),
        selectedImage: null
      };
    },
    watch: {
      isModalVisible(newVal) {
        if (newVal) {
          this.$nextTick(() => {
            this.$refs.inputRef.focus(); // 聚焦到输入框
          });
        }
      }
    },
    created() {
      this.getModelList();
      const value = this.$route.query.id;
      console.log(value)
      if (value) {
        this.formData.searchValue = value;
        this.getImageList(value);
      } else {
        this.imageList = [];

      }


    },
    computed: {

    },
    mounted() {
      //初始化websocket
      this.initWebSocket();
      const saved = localStorage.getItem('labelHistory');
      if (saved) {
        this.labelHistory = JSON.parse(saved);
      }
    },
    methods: {
      onModeChange(e) {
        // 这里把 mode 通过一个方法传给你的画布 / 标注逻辑
        console.log("选择！", e);
        if (e != "rect") {
          if (this.modelTry.modelType == "2") { //只能选择矩形
            this.mode = "rect";
            this.$message.warning("当前模型只能进行矩形标注！");
            return;
          }
        }

      },
      finishEdit() {
        this.editIndex = null; // 编辑完成
      },
      editRect(index) {
        this.editIndex = index; // 进入编辑状态
      },
      autoLabel() {
        console.log("自动识别")
        if (this.formData.searchValue) {
          this.isAutoModalVisible = true;

        } else {
          this.$message.warn(`请先选择需要自动标注的库`);
        }

      },
      //清空历史信息
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
        console.log(this.modelid, "当前页", this.currentPage);

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
            console.log("xxxxxxxxxxxxx", res)
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
            console.log("xxxxxxxxxxxxx", res)
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
            console.log("xxxxxxxxxxxxx", res)
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
      // 新增：选中框的方法
      selectRect(index) {
        this.selectedRectIndex = index;
        this.drawImage(); // 重新绘制以高亮选中的框
      },

      // 新增：删除指定框的方法
      deleteRect(index) {
        this.rectangles.splice(index, 1);
        this.selectedRectIndex = -1; // 重置选中状态
        this.drawImage(); // 重新绘制
      },

      // 新增：处理画布点击事件，用于选中框
      handleCanvasClick(event) {
        // 不处理正在绘制或模态框打开的情况
        console.log("不处理正在绘制或模态框打开的情况")
        if (this.isDrawing || this.isModalVisible) return;
        console.log("处理")
        const canvas = this.$refs.canvas;
        const rect = canvas.getBoundingClientRect();
        const clickX = event.clientX - rect.left;
        const clickY = event.clientY - rect.top;

        // 检查点击位置是否在某个框内
        let foundIndex = -1;
        for (let i = this.rectangles.length - 1; i >= 0; i--) { // 从后往前，优先选中上层的框
          const r = this.rectangles[i];
          if (clickX >= r.x && clickX <= r.x + r.width &&
            clickY >= r.y && clickY <= r.y + r.height) {
            foundIndex = i;
            break;
          }
        }

        this.selectedRectIndex = foundIndex;
        this.drawImage(); // 重新绘制以显示选中状态
      },

      // 新增：清除所有标注的方法
      clearAnnotations() {
        this.rectangles = [];
        this.selectedRectIndex = -1;
        this.drawImage();
      },

      // 标注图片
      selectImage(image) {
        this.currentImage = image;
        this.image.src = image.src;
        console.log("图片宽度", this.image.width);
        console.log("图片长度", this.image.height);
        if (this.currentImage.markFlat == "Y") {
          this.markText = (this.currentImage.markFeature == null ? "标注图" : this.currentImage.markFeature) +
            ":    已完成标注/需要重新标注请重新绘制"
        } else {
          this.markText = "暂无标注";

        }
        this.rectangles = [];
        if (this.currentImage.markJson) {
          let markJson = JSON.parse(this.currentImage.markJson);
          console.log("当前数据", markJson);

          if (markJson && Array.isArray(markJson)) {
            markJson.forEach(item => {
              // 根据你的markJson数据结构调整以下字段映射
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
            });
            console.log("回显绘制", this.rectangles)
          }
        }

        if (this.selectedImage === image) {
          // Deselect if the same image is clicked again
          this.selectedImage = null;
        } else {
          this.selectedImage = image;
        }

      
        this.image.onload = () => {
          this.drawImage();
          console.error('Image onload');
        };

        this.image.onerror = () => {
          console.error('Image failed to load');
        };
        //清空绘制
        // this.rectangles = []
      },

      // 绘制图片到 canvas
      drawImage() {
        if (!this.image.complete) {
          console.error('Image not loaded');
          return;
        }

        const canvas = this.$refs.canvas;
        const ctx = canvas.getContext('2d');
        ctx.clearRect(0, 0, this.canvasWidth, this.canvasHeight);
        ctx.drawImage(this.image, 0, 0, this.canvasWidth, this.canvasHeight);

        // 绘制标注框
        this.rectangles.forEach((rect, index) => {
          ctx.beginPath();
          ctx.rect(rect.x, rect.y, rect.width, rect.height);

          // 根据是否选中设置不同的样式 - 选中用绿色（与左侧选中颜色一致）
          if (index === this.selectedRectIndex) {
            ctx.strokeStyle = '#6dbe52'; // 与左侧选中的绿色一致
            ctx.lineWidth = 4; // 更粗的线条
            ctx.stroke();

            // 添加选中框的背景高亮效果
            ctx.fillStyle = 'rgba(109, 190, 82, 0.2)'; // 半透明绿色背景
            ctx.fillRect(rect.x, rect.y, rect.width, rect.height);
          } else {
            ctx.strokeStyle = 'red'; // 默认红色
            ctx.lineWidth = 2;
            ctx.stroke();
          }

          // 绘制标签文本
          if (rect.label) {
            // 文本背景
            ctx.fillStyle = index === this.selectedRectIndex ? 'rgba(109, 190, 82, 0.8)' : 'rgba(255, 0, 0, 0.8)';
            ctx.fillRect(rect.x, rect.y - 20, ctx.measureText(rect.label).width + 8, 18);

            // 文本内容
            ctx.fillStyle = 'white';
            ctx.font = 'bold 12px Arial';
            ctx.fillText(rect.label, rect.x + 4, rect.y - 6);
          }
        });
      },

      // 鼠标按下，开始绘制
      startDraw(event) {

        if (!this.currentImage) return;
        const canvas = this.$refs.canvas;
        const rect = canvas.getBoundingClientRect();
        this.startX = event.clientX - rect.left;
        this.startY = event.clientY - rect.top;

        if (this.mode == "rect") {
          this.isDrawing = true;
          this.hasMoved = false; // 重置移动标记
          this.selectedRectIndex = -1; // 开始绘制时取消选中
        } else if (this.mode === 'polygon') { //多边形
          // 多边形标注：点击一次增加一个点


        } else if (this.mode === 'control') { //控制点

        }



      },

      // 鼠标移动，绘制中
      drawing(event) {
        if (!this.isDrawing || !this.currentImage) return;
        this.hasMoved = true; // 标记鼠标已移动
        const canvas = this.$refs.canvas;
        const ctx = canvas.getContext('2d');
        const rectWidth = event.clientX - canvas.getBoundingClientRect().left - this.startX;
        const rectHeight = event.clientY - canvas.getBoundingClientRect().top - this.startY;

        ctx.clearRect(0, 0, this.canvasWidth, this.canvasHeight); // 清除之前的标注
        this.drawImage(); // 重新绘制图片和之前的标注框

        ctx.beginPath();
        ctx.rect(this.startX, this.startY, rectWidth, rectHeight);
        ctx.strokeStyle = 'red';
        ctx.lineWidth = 2;
        ctx.stroke();
      },

      // 鼠标抬起，结束绘制
      endDraw(event) {
        if (!this.isDrawing || !this.currentImage) return;
        // 如果鼠标没有移动过，说明是点击而不是拖拽，不创建新框
        if (!this.hasMoved) {
          this.isDrawing = false;
          // 延迟处理点击选中，避免与绘制冲突
          this.$nextTick(() => {
            this.handleCanvasClick(event);
          });
          return;
        }
        const canvas = this.$refs.canvas;
        const rectWidth = event.clientX - canvas.getBoundingClientRect().left - this.startX;
        const rectHeight = event.clientY - canvas.getBoundingClientRect().top - this.startY;
        // 添加绘制的矩形到标注数组
        if (Math.abs(rectWidth) > 5 && Math.abs(rectHeight) > 5) {
          console.log("图片宽度", this.image.width);
          console.log("图片长度", this.currentImage);
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
          // 弹出输入框

          this.showModal(newRect);
        }
        this.isDrawing = false;
        this.hasMoved = false; // 重置移动标记
      },

      // 弹出 Modal 输入标签
      showModal(rect) {
        this.isModalVisible = true;
        //   this.currentLabel = rect.label; // 预填充当前标签
        this.currentRect = rect; // 保存当前矩形，稍后更新
      },
      handleKeyDown(event) {
        alert("event")
      },
      selectHistory(item) {
        this.currentLabel = item;
        // 也可以立即关闭弹窗或直接触发 handleOk()
      },
      // Modal 确认按钮
      handleOk() {
        if (this.currentLabel) {
          const trimmed = this.currentLabel.trim();
          if (trimmed && !this.labelHistory.includes(trimmed)) {
            this.labelHistory.push(trimmed);
            localStorage.setItem('labelHistory', JSON.stringify(this.labelHistory));
          }
          this.currentRect.label = this.currentLabel;
          this.drawImage(); // 重新绘制图片和标注框，显示标签
          this.isModalVisible = false; // 关闭 Modal
        } else {
          this.$message.warning("当前未标注不可提交");
        }
      },

      // Modal 取消按钮
      handleCancel() {
        // 取消时移除最近的矩形
        const index = this.rectangles.indexOf(this.currentRect);
        if (index !== -1) {
          this.rectangles.splice(index, 1);
        }
        this.isModalVisible = false; // 关闭 Modal
        this.drawImage(); // 重新绘制画布
      },
      // 保存标注数据为 XML 文件
      saveAnnotations() {
        const annotations = this.rectangles;
        console.log("保存内容", annotations)
        let picXmlList = [];
        if (annotations) {
          for (let i of annotations) {
            picXmlList.push({
              name: i.label,
              xmin: Number(i.x),
              xmax: Number(i.x) + Number(i.width),
              ymin: Number(i.y),
              ymax: Number(i.y) + Number(i.height),
              ywidth: Number(i.imgwidth),
              yheight: Number(i.imgheight),
              canvaswidth: Number(this.canvasWidth),
              canvasheight: Number(this.canvasHeight),
              modelId: i.modelId,
              picId: i.imgId,
            })
          }
        } else {
          this.$message.warning("当前未标注不可提交");
        }

        console.log("保存内容结果数据", picXmlList)

        console.log("annotations", annotations);
        console.log("picXmlList", picXmlList);
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
        postAction("/train/tabModelTry/addMarkPic", picXmlList).then(res => {
          if (res.success) {
            this.$message.success(`保存成功！`)
            this.getImageList(this.formData.searchValue)
          } else {
            this.$message.warn(`保存失败：` + res.message)
          }
        }).finally(() => {

        })
        //提交到后台处理内容
        //直接保存浏览器文件
        //     .map(rect => {
        //       return `
        //       <object>
        //         <name>${rect.label}</name>
        //         <bndbox>
        //           <xmin>${rect.x}</xmin>
        //           <ymin>${rect.y}</ymin>
        //           <xmax>${rect.x + rect.width}</xmax>
        //           <ymax>${rect.y + rect.height}</ymax>
        //         </bndbox>
        //       </object>
        //     `;
        //     })
        //     .join('');

        //   const xmlContent = `
        //   <annotation>
        //     <folder>images</folder>
        //     <filename>${this.currentImage.name}</filename>
        //     <path>${this.currentImage.src}</path>
        //     ${annotations}
        //   </annotation>
        // `;

        //   const blob = new Blob([xmlContent], {
        //     type: 'application/xml'
        //   });
        //   const url = URL.createObjectURL(blob);
        //   const a = document.createElement('a');
        //   a.href = url;
        //   a.download = `${this.currentImage.name.split('.')[0]}.xml`;
        //   a.click();
        //   URL.revokeObjectURL(url);
      },
      handleOkAuto() {
        let that = this;
        this.$confirm({
          title: "确认要自动识别吗？",
          content: "自动识别后，当前图片库不可动直到自动识别完成！",
          onOk: function() {

            that.autoParame.modelId = that.formData.searchValue;
            postAction("/train/tabModelTry/autoMarkPic", that.autoParame).then(res => {
              if (res.success) {

                that.$message.success(`开始自动识别！`)
                that.getImageList(that.formData.searchValue)
              } else {
                that.$message.warn(`开始自动识别失败！：` + res.message)
              }
            }).finally(() => {
              that.isAutoModalVisible = false; // 关闭 Modal
            })

          }
        });
      },
      deleteAnnotations() {

        console.log(this.currentImage);
        let picId = this.currentImage.id;
        let that = this;
        if (picId) {
          this.$confirm({
            title: "确认删除图片吗？",
            content: "图片删除不可恢复！",
            onOk: function() {

              deleteAction("/easy/tabEasyPic/delete", {
                id: picId
              }).then((res) => {
                if (res.success) {
                  //重新计算分页问题

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
        // WebSocket与普通的请求所用协议有所不同，ws等同于http，wss等同于https
        var userId = store.getters.userInfo.id;
        var url = window._CONFIG['domianURL'].replace("https://", "wss://").replace("http://", "ws://") +
          "/websocket/" + userId;
        console.log(url);
        //update-begin-author:taoyan date:2022-4-22 for:  v2.4.6 的 websocket 服务端，存在性能和安全问题。 #3278
        let token = Vue.ls.get(ACCESS_TOKEN)
        this.websock = new WebSocket(url, [token]);
        this.websock.onopen = this.websocketonopen;
        this.websock.onerror = this.websocketonerror;
        this.websock.onmessage = this.websocketonmessage;
        this.websock.onclose = this.websocketclose;

      },
      websocketonopen: function() {
        this.heartCheckFun();
        console.log("WebSocket连接成功11111");

      },
      websocketonerror: function(e) {
        console.log("WebSocket连接发生错误111111");
      },
      websocketonmessage: function(e) {
        var data = eval("(" + e.data + ")");

        console.log("收到消息makeTitle", data)
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
        // 数据发送
        try {
          this.websock.send(text);
        } catch (err) {
          console.log("send failed (" + err.code + ")");
        }
      },
      heartCheckFun() {
        console.log("发送心跳")

        //心跳检测,每20s心跳一次
        this.heartbeatInterval = setInterval(() => {
          // 发送心跳消息
          this.websocketSend("HeartBeat");
        }, 20000); // 20秒


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

  /* .right-panel-right {
  flex-grow: 1;
  padding: 20px;
} */
  .canvas-container {
    margin-bottom: 20px;
  }

  /* canvas {background: #4c4c4c;} */

  img {
    object-fit: contain;
    /* 保证图片完整显示并保持比例 */
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
    /* Highlight selected item */
    background-color: #f0f8ff;
    /* Optional background color for selected item */
  }

  .selected-indicator {
    position: absolute;
    top: 5px;
    right: 5px;
    color: green;
    font-size: 20px;
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