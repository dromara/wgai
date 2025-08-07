<template>
  <div class="containerOn">
    <!-- 左侧：图片选择列表 -->
    <div class="left-panel">
      <div class="conearchselect" style="100%">
        <j-search-select-tag placeholder="请做出你的选择" @change="handleSelection" v-model="formData.searchValue"
          :dictOptions="searchOptions">
        </j-search-select-tag>
      </div>
      <h3><img src="~@assets/zwyStyle/img/a-8.png"/>标注图片列表</h3>
      <div class="image-list">
        <div v-for="(image, index) in imageList" :key="index"
          :style="{ backgroundColor: isSelected(image) ? '#6dbe52' : '#d2d2d2' }" class="image-item"
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
        <canvas ref="canvas" :width="canvasWidth" :height="canvasHeight" @mousedown="startDraw" @mousemove="drawing"
          @mouseup="endDraw"></canvas>
      </div>

    </div>
    <div class="right-panel">
      <div class="header2">
        <h3 style="float: left;"><img src="~@assets/zwyStyle/img/a-9.png"/>标注结果:</h3>
        <!-- 保存按钮居右 -->
   
        <a-button style="float:right;" type="danger" @click="deleteAnnotations">删除图片</a-button>
        <a-button style="float:right;margin-right:3%" type="primary" @click="saveAnnotations">保存标注</a-button>

      </div>
      <br /><br />
      <div v-if="rectangles.length" style="height: calc(100% - 42px);overflow: auto;">
        <ul>
          <li v-for="(rect, index) in rectangles" :key="index">
            <p style="margin-bottom: 0px;">{{ rect.label }}<span style="margin: 0 5px;">( {{ rect.width }} x {{ rect.height }} )</span>:</p>
            <p style="color: #0364ff;"><span style="margin-right:30px;">X<span style="margin: 0 4px;">:</span>{{ rect.x }}</span><span>Y<span style="margin: 0 4px;">:</span>{{ rect.y }}</span></p>
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
        currentPage: 1,
        pageSize: 25, // 每页显示 8 张图片
        total: 0,
        modelid: '',
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
        markIcon: "✔",
        markText: "暂无标注结果",
        currentImage: null,
        canvasWidth: 700,
        canvasHeight: 700,
        startX: 0,
        startY: 0,
        isDrawing: false,
        rectangles: [],
        label: '',
        currentLabel: '', // 当前输入的标签
        labelHistory: [],
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
    computed: {

    },
    mounted() {
      const saved = localStorage.getItem('labelHistory');
      if (saved) {
        this.labelHistory = JSON.parse(saved);
      }
    },
    methods: {
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
      handleSelection(value) {
        this.formData.searchValue = value;
        this.getImageList(value)
      },
      getImageList(id) {



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
            let a=0;
            for (let i of res.result.records) {
              that.imageList.push({
                id: i.id,
                name: i.picName,
                src: that.ImageUrl + i.picUrl,
                markFlat: i.markType,
                markFeature: i.markFeature
              })
              if(a==0){
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
      // 标注图片
      selectImage(image) {
        this.currentImage = image;
        this.image.src = image.src;
        console.log("图片宽度", this.image.width);
        console.log("图片长度", this.image.height);
        if (this.currentImage.markFlat == "Y") {
          this.markText = (this.currentImage.markFeature == null ? "标注图" : this.currentImage.markFeature) +
            ":已完成标注/需要重新标注请重新绘制"
        } else {
          this.markText = "暂无标注";

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
        this.rectangles = []
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
      }
    },
  };
</script>
<style scoped>
  .containerOn {
    display: flex;
    height:calc(100vh - 145px);
	min-height: 740px;
  }
	.left-panel,.center-panel,.right-panel{box-shadow: 0 0 10px rgba(3, 100, 255, 0.1);border-radius: 10px;background: linear-gradient(to top, #ffffff, #f5faff) !important;}
	.center-panel{width: 720px;padding: 10px;margin-top: 10px;margin-bottom: 10px;}
	.left-panel{width: calc(60% - 380px);min-width: 390px;margin: 10px;}
	.right-panel{width: calc(40% - 380px);min-width:280px;margin: 10px;}
	
	.left-panel h3{color:#49505b;font-size: 14px;margin-top: 10px;}
	.left-panel h3 img{margin-right: 5px;}
	
	.right-panel h3{color:#49505b;font-size: 14px;line-height: 30px;}
	.right-panel h3 img{margin-right: 5px;}
	
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
    cursor: pointer;width: calc(20% - 10px);
  }

  .image-item img {
    width: 100%;
    border: 1px solid #ddd;
    margin-bottom: 5px;
  }
  .image-item p{margin-bottom: 0;}

  .right-panel {
    flex-grow: 1;
    padding:10px;
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
	.conearchselect .ant-select-selection{background: #eff3f8 !important;}
	.containerOn .ant-pagination-item-active a,.containerOn .ant-pagination-item-active:focus a{color: #fff!important;background: #2f51ff;}
	.containerOn .ant-pagination-item-active{border-color:#2f51ff;}
	.containerOn .ant-pagination-item:hover{border-color:#2f51ff;}
	.containerOn .ant-pagination-item:hover a{color:#2f51ff;}
	.containerOn .ant-pagination-prev:focus .ant-pagination-item-link,.containerOn .ant-pagination-next:focus .ant-pagination-item-link,.containerOn .ant-pagination-prev:hover .ant-pagination-item-link, .containerOn .ant-pagination-next:hover .ant-pagination-item-link{border-color: #2f51ff!important;color: #2f51ff!important;}
	
	.containerOn .ant-btn-primary{background-color: #2f51ff;border-color: #2f51ff;}
	.containerOn .ant-btn-danger{color: #ff4d4f;border-color: #ff4d4f;}
</style>