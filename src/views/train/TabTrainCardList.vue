<template>
  <a-card style="margin-top: 24px" :bordered="false" title="模型列表">
    <div slot="extra">
      <a-radio-group @change="handleClickRadio" :value="status">
        <a-radio-button value="">全部</a-radio-button>
        <a-radio-button value="1">已有模型</a-radio-button>
        <a-radio-button value="0">未有模型</a-radio-button>
      </a-radio-group>

    <!--  <j-dict-select-tag @change="handleChange" type="select" style="margin-left: 16px; width: 272px;"
        :value="deviceType" :triggerChange="true" placeholder="选择设备类型" dictCode="b_production,prod_name,id" /> -->
      <!--      <a-input-search style="margin-left: 16px; width: 272px;" />-->

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
                <img class="card-avatar" :src="imgPath+item.modelPic" />
                <span style="margin-left: 15px;font-size: 20px;color:#1890FF ;">
                  {{ item.modelName }}
                </span>

                <hr class="custom-hr">

                <div>
                  <span
                    style="font-size: 12px;color:#878484 ;  word-wrap: break-word; word-break: break-all; white-space: normal;width: 100%;">
                    {{ item.ramerk }}
                  </span>
                  </br>
                  <a-icon style="color: #2292DD;" type="share-alt" />
                  <span style="font-size: 14px;color:#1890FF ;">模型状态:</span>
                  <span v-if="item.onnxIsok==1" style="margin-left: 10px"
                    class="ant-badge-status-dot ant-badge-status-success"></span>
                  <span v-if="item.onnxIsok==0" style="margin-left: 10px"
                    class="ant-badge-status-dot ant-badge-status-error"></span>
                  <span style="font-size: 12px"> {{item.onnxIsok==1?'已有模型':'未有模型'}}</span>
                  <!--             </br> -->
                  <a-icon style="margin-left: 15px;color: #2292DD;" type="share-alt" />
                  <span style=" font-size: 14px;color:#1890FF ;">运行状态:</span>
                  <span v-if="item.runState==1" style="margin-left: 10px"
                    class="ant-badge-status-dot ant-badge-status-success"></span>
                  <span v-if="item.runState==0" style="margin-left: 10px"
                    class="ant-badge-status-dot ant-badge-status-error"></span>
                  <span style="margin-left: 5px;font-size: 12px">{{item.runState==0?'未在训练':'正在训练'}}</span>
                  </br>
                  <div style="width: 49%;float:left">
                    <a-icon style="color: #2292DD;" type="file-jpg" />
                    <span style=" font-size: 14px;color:#1890FF ;">图片数:</span>
                    <span style="margin-left: 5px;font-size: 12px">{{item.picNumber}}</span>
                  </div>
                  <div style="width: 49%;float:left">
                    <a-icon style="color: #2292DD;" type="tags" />
                    <span style=" font-size: 14px;color:#1890FF ;">标记数:</span>
                    <span style="margin-left: 5px;font-size: 12px">{{item.makeNumber==null?0:item.makeNumber}}</span>
                  </div>
                  <div style="width: 49%;float:left">
                    <a-icon style="color: #2292DD;" type="tag" />
                    <span style=" font-size: 14px;color:#1890FF ;">未标记数:</span>
                    <span style="margin-left: 5px;font-size: 12px">{{item.picNumber-item.makeNumber}}</span>
                  </div>
                  <div style="width: 49%;float:left">
                    <a-icon style="color: #2292DD;" type="save" />
                    <span style=" font-size: 14px;color:#1890FF ;">图片总大小:</span>
                    <span style="margin-left: 5px;font-size: 12px">{{item.fileSize==null?'0MB':item.fileSize}}</span>
                  </div>
                  <div style="width: 100%;float:left">
                    <a-icon style="color: #2292DD;" type="box-plot" />
                    <span style=" font-size: 14px;color:#1890FF ;">标注进度:</span>
                    <span style="margin-left: 5px;font-size: 12px"> <a-progress
                        :percent="item.makeNumber/item.picNumber*100" style="width: 100%" /></span>
                  </div>
                  <div style="width: 100%;float:left">
                    <a-icon style="color: #2292DD;" type="file" />
                    <span style=" font-size: 14px;color:#1890FF ;">模型标签:</span>
                    <span
                      style="margin-left: 5px;font-size: 12px">{{item.txtInfo }}</span>
                  </div>
                  <div style="width: 100%;float:left">
                    <a-icon style="color: #2292DD;" type="file-jpg" />
                    <span style=" font-size: 14px;color:#1890FF ;">创建时间:</span>
                    <span
                      style="margin-left: 5px;font-size: 12px">{{item.createTime !=null?item.createTime:'????-??-?? ??:??:??' }}</span>
                  </div>
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
                  <a-icon type="radar-chart" /> 训练模型
                </a>
              </div>
              <div>
                <a @click="handleMake(item)" style="color: #0c18d7;">
                  <a-icon type="tags" />图片标注
                </a>
              </div>
              <div v-if="item.onnxIsok==1">
                <a @click="showResult(item)" style="color: #c919e4;">
                  <a-icon type="snippets" />训练结果
                </a>
              </div>
              <div v-if="item.onnxIsok==1">
                <a @click="showLog(item)" style="color: #f26c0b;">
                  <a-icon type="snippets" />训练日志
                </a>
              </div>
              <div v-if="item.onnxIsok==1">
                <a @click="showSendView(item.modelOnnx)" style="color: green;">
                  <a-icon type="download" />模型下载
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
          color: #1890ff;
        }
      }
    }
  }

  .custom-hr {
    border: none;
    border-top: 1px solid #1890FF;
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