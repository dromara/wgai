<template>
  <div class="container">
    <!-- 左侧：图片选择列表 -->
    <div class="left-panel">
      <div style="100%">
        <j-search-select-tag placeholder="请做出你的选择" @change="handleSelection" v-model="formData.searchValue"
          :dictOptions="searchOptions">
        </j-search-select-tag>
      </div>






      <h3>标注图片列表 ∨</h3>
      <div class="image-list">
        <div v-for="(image, index) in imageList" :key="index" class="image-item" @click="selectImage(image)">
          <img :src="image.src" :alt="image.name" />
          <p style="font-size: 13px;">{{ image.name }}</p>
          <span v-if="isSelected(image)||image.markFlat=='Y'" class="selected-indicator">{{markIcon}}</span>
        </div>
       
      </div>
    </div>

    <!-- 右侧：画布和标注操作区域 -->
    <div class="center-panel">
      <div class="canvas-container">
        <canvas ref="canvas" :width="canvasWidth" :height="canvasHeight" @mousedown="startDraw" @mousemove="drawing"
          @mouseup="endDraw"></canvas>
      </div>

    </div>
    <div class="right-panel">
      <div class="header2">
        <h3 style="float: left;">标注结果:</h3>
        <!-- 保存按钮居右 -->
        <a-button style="float:right" type="primary" @click="saveAnnotations">保存</a-button>
      </div>
      <br /><br />
      <div v-if="rectangles.length">
        <ul>
          <li v-for="(rect, index) in rectangles" :key="index">
            <p>标签: {{ rect.label }}</p>
            <p>位置: ({{ rect.x }}, {{ rect.y }})</p>
            <p>大小: {{ rect.width }} x {{ rect.height }}</p>
          </li>
        </ul>
      </div>
      <div v-else>
        <p>{{markText}}</p>
      </div>
    </div>

    <!-- 使用 Ant Design Vue 的 Modal 弹框输入标签 -->
    <a-modal v-model="isModalVisible" title="请输入标注名称"   @open="handleModalVisibleChange"  @ok="handleOk" @cancel="handleCancel">
      <a-input    ref="inputRef" v-model="currentLabel" placeholder="输入标注名称"  @keydown.enter="handleOk" />
    </a-modal>

  </div>

</template>




<script>
  import JSearchSelectTag from '@/components/dict/JSearchSelectTag'
  import {
    httpAction,
    getAction,
    postAction
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
        formData: {},
        searchOptions: [{
          text: "选项一",
          value: "1"
        }, {
          text: "选项二",
          value: "2"
        }, {
          text: "选项三",
          value: "3"
        }],
        ImageUrl: "",
        imageList: [{
            name: 'Image 1',
            src: 'image1.jpg',
            id: ""
          },

        ],
        markIcon:"✔",
        markText:"暂无标注结果",
        currentImage: null,
        canvasWidth: 700,
        canvasHeight: 700,
        startX: 0,
        startY: 0,
        isDrawing: false,
        rectangles: [],
        label: '',
        currentLabel: '', // 当前输入的标签
        isModalVisible: false, // 控制 Modal 显示与隐藏
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
    methods: {
     
      handleSelection(value) {
        this.formData.searchValue = value;
        this.getImageList(value)
      },
      getImageList(id) {

        this.ImageUrl = `${window._CONFIG['domianURL']}/sys/common/static/`;

        let that = this;
        getAction("/train/tabModelTry/listPic", {
          id: id
        }).then((res) => {
          if (res.success) {
            console.log("xxxxxxxxxxxxx", res)

            that.imageList = [];
            for (let i of res.result) {
              that.imageList.push({
                id: i.id,
                name: i.picName,
                src: that.ImageUrl + i.picUrl,
                markFlat:i.markType
              })
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
      // 标注图片
      selectImage(image) {
        this.currentImage = image;
        this.image.src = image.src;
        console.log("图片宽度", this.image.width);
        console.log("图片长度", this.image.height);
        if(this.currentImage.markFlat=="Y"){
          this.markText="已完成标注/需要重新标注请重新绘制"
        }else{
          this.markText="暂无标注";
  
        }
        if (this.selectedImage === image) {
          // Deselect if the same image is clicked again
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
        //清空绘制
        this.rectangles=[]
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
        this.rectangles.forEach(rect => {
          ctx.beginPath();
          ctx.rect(rect.x, rect.y, rect.width, rect.height);
          ctx.strokeStyle = 'red';
          ctx.lineWidth = 2;
          ctx.stroke();
        });
      },

      // 鼠标按下，开始绘制
      startDraw(event) {
        if (!this.currentImage) return;
        const canvas = this.$refs.canvas;
        const rect = canvas.getBoundingClientRect();
        this.startX = event.clientX - rect.left;
        this.startY = event.clientY - rect.top;
        this.isDrawing = true;
      },

      // 鼠标移动，绘制中
      drawing(event) {
        if (!this.isDrawing || !this.currentImage) return;
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
        const canvas = this.$refs.canvas;
        const rectWidth = event.clientX - canvas.getBoundingClientRect().left - this.startX;
        const rectHeight = event.clientY - canvas.getBoundingClientRect().top - this.startY;
        // this.
        // 添加绘制的矩形到标注数组

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
        this.isDrawing = false;

        // 弹出输入框
        this.showModal(newRect);
      },

      // 弹出 Modal 输入标签
      showModal(rect) {
        this.isModalVisible = true;
        this.currentLabel = rect.label; // 预填充当前标签
        this.currentRect = rect; // 保存当前矩形，稍后更新
      },
      handleKeyDown(event){
        alert("event")
      },
      // Modal 确认按钮
      handleOk() {
        if (this.currentLabel) {
          this.currentRect.label = this.currentLabel;
          this.drawImage(); // 重新绘制图片和标注框，显示标签
          this.isModalVisible = false; // 关闭 Modal
        }else{
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
        let picXmlList = [];
        if (annotations) {
          for (let i of annotations) {
            picXmlList.push({
              name: i.label,
              xmin: i.x,
              xmax: i.x + i.width,
              ymin: i.y,
              ymax: i.y + i.height,
              ywidth: i.imgwidth,
              yheight: i.imgheight,
              canvaswidth: this.canvasWidth,
              canvasheight: this.canvasHeight,
              modelId: i.modelId,
              picId: i.imgId,
            })
          }
        } else {
          this.$message.warning("当前未标注不可提交");
        }



        console.log("annotations", annotations);
        console.log("picXmlList", picXmlList);
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
    },
  };
</script>
<style scoped>
  .container {
    display: flex;
    height: 100vh;
  }

  .left-panel {
    width: 250px;
    padding: 10px;
    overflow-y: auto;
    border-right: 1px solid #ddd;
  }

  .image-list {
    display: flex;
    flex-wrap: wrap;
  }

  .image-item {
    width: 80px;
    margin: 5px;
    text-align: center;
    cursor: pointer;
  }

  .image-item img {
    width: 100%;
    border: 1px solid #ddd;
    margin-bottom: 5px;
  }

  .right-panel {
    flex-grow: 1;
    padding: 20px;
  }

  /* .right-panel-right {
  flex-grow: 1;
  padding: 20px;
} */
  .canvas-container {
    margin-bottom: 20px;
  }

  canvas {
    border: 1px solid #000;
  }

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
    border: 2px solid #00f; /* Highlight selected item */
    background-color: #f0f8ff; /* Optional background color for selected item */
  }
  
  .selected-indicator {
    position: absolute;
    top: 5px;
    right: 5px;
    color: green;
    font-size: 20px;
  }
</style>