<template>
  <a-card class="concard" :bordered="false" title="模型列表">
    <div slot="extra">
    <a-button type="primary"  style="margin-right:20px" @click="startMakeNum()" > <a-icon type="snippets" />更新标记数</a-button>
     
      <a-radio-group @change="handleClickRadio" :value="status">
        <a-radio-button value="">●&nbsp;全部</a-radio-button>
        <a-radio-button value="1">●&nbsp;已有模型</a-radio-button>
        <a-radio-button value="0">●&nbsp;未有模型</a-radio-button>
      </a-radio-group>
          
  <!-- <j-dict-select-tag @change="handleChange" type="select" style="margin-left: 16px; width: 272px;"
        :value="deviceType" :triggerChange="true" placeholder="选择设备类型" dictCode="b_production,prod_name,id" /> -->
      <!--      <a-input-search style="margin-left: 16px; width: 272px;" /> -->

    </div>
    <a-list :grid="{gutter: 24, lg: 4, md: 2, sm: 1, xs: 1}" :dataSource="dataSource" :pagination="pagination">
      <a-list-item slot="renderItem" slot-scope="item, index">
        <template v-if="item === null">
          <!--<a-button class="new-btn" type="dashed">-->
          <!--<a-icon type="plus"/>-->
          <!--新增产品-->
          <!--</a-button>-->
          <a-card style="margin-top: 24px;text-align: center" :bordered="true">
            <div class="no-data"><a-icon type="frown-o" />暂无数据</div>
          </a-card>
        </template>
        <template v-else>
          <a-card :hoverable="true">
            <a-card-meta @click="goDeviceInfo(item.id)">
              <div style="margin-bottom: 2px;" slot="title">
                <!-- <img class="card-avatar" v-if="item.modelPic!=null" :src="imgPath+item.modelPic" />
                <img class="card-avatar" v-if="item.modelPic==null" src="/logo.png" /> -->
               <div class="title">
				   <span class="span" style="">
				     {{ item.modelType_dictText }}-{{ item.modelName }}				   
				   </span>
				   <span class="span2">创建时间<span>{{item.createTime !=null?item.createTime:'????-??-?? ??:??:??' }}</span></span>
			   </div>

                <div class="txt">
                  <!-- <span
                    style="font-size: 12px;color:#878484 ;  word-wrap: break-word; word-break: break-all; white-space: normal;width: 100%;">
                    {{ item.ramerk }}
                  </span> -->
                  <div style="width: 49%;float:left">
					  <span style="font-size: 14px;color:#51617b ;">模型状态:</span>
					  <span v-if="item.onnxIsok==1" class="yy">已有模型</span>
					  <span v-if="item.onnxIsok==0" class="wy">未有模型</span>
					  <!-- <span style="font-size: 12px"> {{item.onnxIsok==1?'已有模型':'未有模型'}}</span> -->
                              <!-- </br> -->
                  </div>
                  <div style="width: 49%;float:left">
					  <span style=" font-size: 14px;color:#51617b ;">运行状态:</span>
					  <span v-if="item.runState==1" class="yy">正在训练</span>
					  <span v-if="item.runState==0" class="wy">未在训练</span>
					  <!-- <span style="margin-left: 5px;font-size: 12px">{{item.runState==0?'未在训练':'正在训练'}}</span> -->
                  </div>
                  <div style="width: 49%;float:left">
                    <span style=" font-size: 14px;color:#51617b ;">图片数:</span>
                    <span style="margin-left: 5px;font-size: 12px;color:#0364ff ;">{{item.picNumber}}</span>
                  </div>
                  <div style="width: 49%;float:left">
                    <span style=" font-size: 14px;color:#51617b ;">标记数:</span>
                    <span style="margin-left: 5px;font-size: 12px;color:#0364ff ;">{{item.makeNumber==null?0:item.makeNumber}}</span>
                  </div>
                  <div style="width: 49%;float:left">
                    <span style=" font-size: 14px;color:#51617b ;">未标记数:</span>
                    <span style="margin-left: 5px;font-size: 12px;color:#0364ff ;">{{item.picNumber-item.makeNumber}}</span>
                  </div>
                  <div style="width: 49%;float:left">
                    <span style=" font-size: 14px;color:#51617b ;">图片总大小:</span>
                    <span style="margin-left: 5px;font-size: 12px;color:#0364ff ;">{{item.fileSize==null?'0':item.fileSize}}MB</span>
                  </div>
                  <div style="width: 100%;float:left">
                    <span style=" font-size: 14px;color:#51617b ;">标注进度:</span>
                    <div style="display: block; width: 100%;height: 19px;">
						<span style="margin-left: 5px;font-size: 12px;color:#0364ff ;position: relative;top: -6px;"> <a-progress
						    :percent="item.makeNumber/item.picNumber*100" style="width: 100%" /></span>
					</div>
                  </div>
                  <div style="width: 100%;float:left">
                    <span style=" font-size: 14px;color:#51617b ;">模型标签:</span>
                    <div style="display: block; width: 100%;height: 19px;">
						<span style="margin-left: 5px;font-size: 12px;color:#0364ff ;position: relative;top: -6px;"> {{item.txtInfo }}</span>
					  </div>
                  </div>
                  <!-- <div style="width: 100%;float:left">
                    <a-icon style="color: #2292DD;" type="file-jpg" />
                    <span style=" font-size: 14px;color:#0364ff ;">创建时间:</span>
                    <span
                      style="margin-left: 5px;font-size: 12px">{{item.createTime !=null?item.createTime:'????-??-?? ??:??:??' }}</span>
                  </div> -->
                </div>

              </div>

              <!-- <div class="meta-cardInfo" slot="description">
                <div>
                  <p>图片数量</p>
                  <p>
                    <span><span style="color: #2292DD;">{{ item.picNumber }}</span></span>
                  </p>
                </div>
                <div>
                  <p>上次在线时间</p>
                  <p style="font-size: 11px;color: #2292DD;">{{item.activeTime !=null?item.activeTime:'????-??-?? ??:??:??' }}</p>
                </div>
              </div> -->
            </a-card-meta>
            <template class="ant-card-actions" slot="actions">
              <div>
                <a @click="startTrain(item)" style="color: red;">
                  训练模型
                </a>
              </div>
              <div>
                <a @click="handleMake(item)" style="color: #0c18d7;">
                  图片标注
                </a>
              </div>
              <div v-if="item.onnxIsok==1">
                <a @click="showResult(item)" style="color: #c919e4;">
                  训练结果
                </a>
              </div>
              <div v-if="item.onnxIsok==1">
                <a @click="showLog(item)" style="color: #f26c0b;">
                  训练日志
                </a>
              </div>
              <div v-if="item.onnxIsok==1">
                <a @click="showSendView(item.modelOnnx)" style="color: green;">
                  模型下载
                </a>
              </div>
              <!--  <a> -->
              <!--          <a-dropdown>
                  <a class="ant-dropdown-link" href="javascript:;">
                    <a-icon type="ellipsis"/>
                  </a>
                  <a-menu slot="overlay" >
                        <a-menu-item v-for="(instruction, index3) in item.instructions" :key="instruction.id">
                          <a @click="sendWithInsId(item.uid,instruction.id,instruction.insTitle,item.isActive)" href="javascript:;">{{instruction.insTitle}}</a>
                        </a-menu-item>
                  </a-menu>
                </a-dropdown> -->
              <!--     </a> -->
              <!--</a>-->
            </template>
          </a-card>
        </template>
      </a-list-item>
    </a-list>
    <tab-train-result-modal-show ref="modalForm"></tab-train-result-modal-show>
    <tab-train-log-modal-show ref="modalFormLog"></tab-train-log-modal-show>
  </a-card>
</template>

<script>
  import {
    getAction,
    postAction,
    downFile,
    getFileAccessHttpUrl
  } from '@/api/manage'
  import {
    JeecgListMixin
  } from '@/mixins/JeecgListMixin'
  import TabTrainResultModalShow from './modules/TabTrainResultModalShow'
    import TabTrainLogModalShow from './modules/TabTrainLogModalShow'
  import {
    mixinDevice
  } from '@/utils/mixin'
  let pageSize = 8;
  export default {
    name: "TabTrainCardList",

    components: {
      TabTrainResultModalShow,
      TabTrainLogModalShow
    },
    data() {
      return {
        dataSource: [],
        pagination: {
          // showSizeChanger: true,
          // showQuickJumper: true,
          total: 0,
          pageSize,
          onChange: this.onChange,
          // onShowSizeChange:this.onShowSizeChange
        },
        deviceType: '',
        status: '',
        imgPath: ''
      }
    },
    created() {
      this.imgPath = `${window._CONFIG['domianURL']}/`;
      console.log("this.imgPath", this.imgPath)
      let condition = {
        pageSize: 8,
        pageNo: 1
      };
      this.getDevices(condition);
    },
    mounted() {},
    computed: {


    },
    methods: {
      startMakeNum(){
            let that = this;
        this.$confirm({
          title: '提示',
          content: "确认更新标记数吗？",
          onOk() {
            getAction("/train/tabModelTry/startMakeNum").then((res) => {
              if (res.success) {
        
                that.$message.success("更新成功");
        
              } else {
                that.$message.warning("更新失败");
              }
            }).finally(() => {
              that.reloadData();
            })
          },
      
        });
      },
      getDevices(condition) {
        let that = this;
        console.log(condition);
        getAction('/train/tabModelTry/list', condition).then((res) => {
          if (res.success) {
            let page = res.result;
            console.log(page);
            that.pagination.total = page.total;
            that.pagination.pageSize = page.size;
            that.dataSource = page.records;
          }
        })
      },
      onChange(page, pageSize) {
        let condition = {
          pageSize: 8,
          pageNo: page
        };
        console.log(page);
        this.getDevices(condition);
        // this.pageNumber = page
        // this.loadData({ pageNum: page })
      },
      handleChange(val) {
        this.deviceType = val;
        this.reloadData();
      },
      handleClickRadio(e) {
        this.status = e.target.value;
        this.reloadData();
      },
      reloadData() {
        let condition = {
          pageSize: 8,
          pageNo: 1,
          onnxIsok :this.status,
          isActive: this.status,
          prodId: this.deviceType
        }
        this.getDevices(condition);
      },
      goDeviceInfo(deviceId) {
        console.log(deviceId);
        // this.$router.push({
        //   path: `/device/channel/${deviceId}`
        // });
      },
      showSendView(text) {
        if (text) {
          console.log(text)
          if (!text) {
            this.$message.warning("未知的文件")
            return;
          }
          if (text.indexOf(",") > 0) {
            text = text.substring(0, text.indexOf(","))
          }
          let url = getFileAccessHttpUrl(text)
          window.open(url);
        } else {
          this.$message.error("模型不存在");
        }
      },
      startTrain(item) { //开始训练
        // this.$message.error("演示demo禁止训练！");
        // return;
        let that = this;
        let content = "未完全标记确认要训练吗?";
        if (item.makeNumber <= 0 || item.picNumber <= 0) {
          this.$message.error("当前未标注图片无法训练！");
          return;
        }
        if (item.makeNumber >= item.picNumber) {
          content = "已完成图片标注确认要训练吗？";
        }
        if (item.onnxIsok == 1) {
          content = "当前模型已经存在重新训练会覆盖原始模型，" + content;
        }
        this.$confirm({
          title: '提示',
          content: content,
          onOk() {
            getAction("/train/tabTrainPython/startPy", {
              id: item.id
            }).then((res) => {
              if (res.success) {

                that.$message.success("开始训练");

              } else {
                that.$message.warning("训练失败");
              }
            }).finally(() => {
              that.reloadData();
            })
          },
          onCancel() {

          },
        });

      },
      handleMake(item) {
        console.log(item);
        if (item.picNumber <= 0) {
          this.$message.error("当前未上传图片，无法标注！");
          return;
        }
        this.$router.push({
          path: 'canvas/makeTitle',
          query: {
            id: item.id
          }
        });


      },
      showResult(item) {
        let that = this;
        getAction("/train/tabTrainResult/queryByModelId", {
          id: item.id
        }).then((res) => {
          if (res.success) {

            that.$refs.modalForm.edit(res.result);
            that.$refs.modalForm.title = "训练结果";
            that.$refs.modalForm.disableSubmit = true;

          } else {
            that.$message.warning("获取训练结果失败");
          }
        })

      },
      showLog(item) {
        let that = this;
        getAction("/train/tabTrainLog/queryByModelId", {
          id: item.id
        }).then((res) => {
          if (res.success) {
      
            that.$refs.modalFormLog.edit(res.result);
            that.$refs.modalFormLog.title = "训练日志";
            that.$refs.modalFormLog.disableSubmit = true;
      
          } else {
            that.$message.warning("获取训练结果失败");
          }
        })
      
      },
      sendWithInsId(uid, insId, title, isOnline) {
        var that = this;
        if (isOnline == 0) {
          this.$notification.error("设备不在线");
        } else {
          this.$confirm({
            title: '提示',
            content: '真的要发送 ' + title + ' 指令吗 ?',
            onOk() {
              postAction('/env/device/ctrl/sendWithInsId', {
                uid: uid,
                insId: insId
              }).then((res) => {
                if (res.success) {
                  that.$message.success(res.message);
                } else {
                  that.$message.error(res.message);
                }
              })
            },
            onCancel() {},
          });
        }
      }
    },

  }
</script>

<style scoped>
  .card-avatar {
    width: 48px;
    height: 48px;
    border-radius: 48px;
  }

  .ant-card-actions {
    background: #f7f9fa;

    li {
      float: left;
      text-align: center;
      margin: 12px 0;
      color: rgba(0, 0, 0, 0.45);
      width: 50%;

      &:not(:last-child) {
        border-right: 1px solid #e8e8e8;
      }

      a {
        color: rgba(0, 0, 0, .45);
        line-height: 22px;
        display: inline-block;
        width: 100%;

        &:hover {
          color: #0364ff;
        }
      }
    }
  }

  .custom-hr {
    border: none;
    border-top: 1px solid #0364ff;
    /* 设置分割线的颜色和厚度 */
    width: 100%;
    /* 设置分割线的宽度为100% */
    margin: 2px 0;
    /* 上下间距 */
  }

  .new-btn {
    background-color: #fff;
    border-radius: 2px;
    width: 100%;
    height: 188px;
  }

  .meta-content {
    position: relative;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    height: 64px;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
  }

  .meta-cardInfo {
    zoom: 1;
    margin-top: 16px;

    >div {
      position: relative;
      text-align: left;
      float: left;
      width: 50%;

      p {
        line-height: 32px;
        font-size: 24px;
        margin: 0;

        &:first-child {
          color: rgba(0, 0, 0, .45);
          font-size: 12px;
          line-height: 20px;
          margin-bottom: 4px;
        }
      }

    }
  }
</style>

<style  lang="less">
	.concard{
		background: none!important;
		.ant-card-head{
			border: none;
			.ant-card-head-wrapper{height: 44px;}
			.ant-card-head-title{background:18px 35px url(~@assets/zwyStyle/img/bg-05.png) no-repeat;padding:10px 0!important;}
			.ant-card-extra{padding:0;
				>div{display: flex;flex-direction: row;justify-content: center;align-items: center;}
				.ant-btn.ant-btn-primary{border-radius: 100px;}
				.ant-radio-group{border-radius: 100px;border: 1px solid #dcdcdc;padding: 2px;background: #eff3f8;overflow: hidden;}
				.ant-radio-button-wrapper{background: none;border: none;color: #a0a6b1;border-radius: 100px;}
				.ant-radio-button-wrapper-checked{border-radius: 100px;background: #0364ff;color: #fff;}
				.ant-radio-button-wrapper::before{display: none;}
			}
		}
		.ant-card-body{padding:15px 10px;overflow: hidden;
			.ant-card{background:#fff top url(~@assets/zwyStyle/img/bg-06.png) no-repeat;background-size: 100%;box-shadow: 0 0 10px rgba(3,100,255,0.1);border: none;border-radius: 10px;overflow: hidden;}
			
			.title{display: flow-root;height: 30px;background:left center url(~@assets/zwyStyle/img/a-5.png) no-repeat;background-size: 20px;}
			.span{margin-left:23px;font-size:14px;color:#0364ff;line-height: 30px;float: left;}
			.span2{font-size: 12px;color: #606e85;float: right;line-height: 30px;}
			.ant-card-actions{background: none;}
			.txt{background: #eff3f8;display: inline-block;width: 100%;padding: 5px;border-radius: 10px;margin-top:0px;}
			.txt>div{margin: 2px 0;}
			.ant-progress-inner{background: #dde2e8;}
			.ant-progress-status-success .ant-progress-bg{background-color:#80c269 !important;}
			.anticon{color:#80c269 !important;}
			.ant-card-actions > li{margin: 5px 0;border: none!important;position: relative;}
			.ant-card-actions > li:before{content: '';position: absolute;width: 1px;height: 15px;background: #adb2bb;right: 0;top: calc(50% - 7.5px);}
			.ant-card-actions > li:last-child:before{display: none;}
			.ant-card-actions > li>span>div{height: 34px;}
			.ant-card-actions > li>span>div>a{line-height: 34px;}
		}
	}
	.ant-card-actions > li > span a:not(.ant-btn), .ant-card-actions > li > span > .anticon{white-space:nowrap; text-overflow:ellipsis; -o-text-overflow:ellipsis; overflow: hidden; }
	.yy{margin-left: 10px;font-size: 12px;color: #80c269;border: 1px solid #80c269;background: #eaf5e7;padding: 0 3px;border-radius: 4px;}
	.wy{margin-left: 10px;font-size: 12px;color: #eb6877;border: 1px solid #eb6877;background: #f5e4e4;padding: 0 3px;border-radius: 4px;}
</style>