<template>
  <a-card :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="订阅名称">
              <a-input placeholder="订阅名称" v-model="queryParam.name"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="回调地址">
              <a-input placeholder="回调地址" v-model="queryParam.eventUrl"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
              <a-button type="primary" @click="searchQuery" class="cx" icon="search">查询</a-button>
              <a-button type="primary" @click="searchReset" class="cz" icon="reload" style="margin-left: 8px">重置</a-button>
              <!-- <a @click="handleToggleSearch" style="margin-left: 8px">
                {{ toggleSearchStatus ? '收起' : '展开' }}
                <a-icon :type="toggleSearchStatus ? 'up' : 'down'"/>
              </a> -->
            </span>
          </a-col>
          
        </a-row>
      </a-form>
    </div>
    <!-- 查询区域-END -->

    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAdd" type="primary" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" @click="handleExportXls('多程第三方订阅')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <j-super-query :fieldList="superFieldList" ref="superQueryModal" @handleSuperQuery="handleSuperQuery"></j-super-query>
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel"><a-icon type="delete"/>删除</a-menu-item>
          <a-menu-item key="1" @click="batchStart('1')"><a-icon type="primary"/>批量启动</a-menu-item>
          <a-menu-item key="1" @click="batchStart('0')"><a-icon type="primary"/>批量停止</a-menu-item>
        </a-menu>
      
        <a-button style="margin-left: 8px"> 批量操作 <a-icon type="down" /></a-button>
      </a-dropdown>
    </div>

    <!-- table区域-begin -->
    <div>
      <div class="ant-alert ant-alert-info" style="margin-bottom: 16px;">
        <i class="anticon anticon-info-circle ant-alert-icon"></i> 已选择 <a style="font-weight: 600">{{ selectedRowKeys.length }}</a>项
        <a style="margin-left: 24px" @click="onClearSelected">清空</a>
      </div>

      <a-table
        ref="table"
        size="middle"
        :scroll="{x:true}"
        bordered
        rowKey="id"
        :columns="columns"
        :dataSource="dataSource"
        :pagination="ipagination"
        :loading="loading"
        :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}"
        class="j-table-force-nowrap"
        @change="handleTableChange">

        <template slot="htmlSlot" slot-scope="text">
          <div v-html="text"></div>
        </template>
        <template slot="imgSlot" slot-scope="text,record">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无图片</span>
          <img v-else :src="getImgView(text)" :preview="record.id" height="25px" alt="" style="max-width:80px;font-size: 12px;font-style: italic;"/>
        </template>
        <template slot="fileSlot" slot-scope="text">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无文件</span>
          <a-button
            v-else
            :ghost="true"
            type="primary"
            icon="download"
            size="small"
            @click="downloadFile(text)">
            下载
          </a-button>
        </template>

        <span slot="action" slot-scope="text, record">
          <a @click="handleEdit(record)">编辑</a>
          <a-divider type="vertical" v-if="record.runState==0" /> 
          <a  v-if="record.runState==0"  @click="handleRun(record,1)">开始执行</a>
          <a-divider type="vertical"  v-if="record.runState==1"/>
          <a  v-if="record.runState==1"  @click="handleStop(record,0)">结束执行</a>
          <a-divider type="vertical" />
         <!-- <a @click="handleEditStart(record)">设置边界</a>
          <a-divider type="vertical" /> -->
          <a-dropdown>
            <a class="ant-dropdown-link">更多 <a-icon type="down" /></a>
            <a-menu slot="overlay">
              <a-menu-item>
                <a @click="handleDetail(record)">详情</a>
              </a-menu-item>
              <a-menu-item>
                <a-popconfirm title="确定删除吗?" @confirm="() => handleDelete(record.id)">
                  <a>删除</a>
                </a-popconfirm>
              </a-menu-item>
            </a-menu>
          </a-dropdown>
        </span>

      </a-table>
    </div>

    <tab-ai-subscription-new-modal ref="modalForm" @ok="modalFormOk"></tab-ai-subscription-new-modal>
    <pic-util class="contc" :width="1200" ref="modalForm2" @ok="modalFormOk"></pic-util>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabAiSubscriptionNewModal from './modules/TabAiSubscriptionNewModal'
  import {filterMultiDictText} from '@/components/dict/JDictSelectUtil'
  import {
    httpAction,
    getAction
  } from '@/api/manage'
    import picUtil from './modules/picUtil'
  export default {
    name: 'TabAiSubscriptionNewList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabAiSubscriptionNewModal,picUtil
    },
    data () {
      return {
        description: '多程第三方订阅管理页面',
        // 表头
        columns: [
          {
            title: '#',
            dataIndex: '',
            key:'rowIndex',
            width:60,
            align:"center",
            customRender:function (t,r,index) {
              return parseInt(index)+1;
            }
          },
          {
            title:'订阅名称',
            align:"center",
            dataIndex: 'name'
          },
          {
            title:'执行状态',
            align:"center",
            dataIndex: 'runState_dictText'
          },
          {
            title:'解码脚本',
            align:"center",
            dataIndex: 'pyType_dictText'
          },
          {
            title:'推理方式',
            align:"center",
            dataIndex: 'modelJmType_dictText'
          },
        
          {
            title:'解码方式',
            align:"center",
            dataIndex: 'eventTypes_dictText'
          },
          {
            title:'是否推送',
            align:"center",
            dataIndex: 'pushStatic_dictText'
          },
          {
            title:'订阅地址',
            align:"center",
            dataIndex: 'beginEventTypes'
          },
          {
            title:'订阅回调地址',
            align:"center",
            dataIndex: 'eventUrl'
          },
          {
            title:'设备编号',
            align:"center",
            dataIndex: 'indexCode'
          },
          {
            title:'同类型报警间隔(S/秒)',
            align:"center",
            dataIndex: 'eventNumber'
          },
          // {
          //   title:'报警消息',
          //   align:"center",
          //   dataIndex: 'eventInfo'
          // },
       
          // {
          //   title:'推送状态',
          //   align:"center",
          //   dataIndex: 'pushStatic'
          // }, 
         
           {
             title:'是否分析录像',
             align:"center",
             dataIndex: 'isBegin_dictText'
           },
         
        
          // {
          //   title:'播报状态', 
          //   align:"center",
          //   dataIndex: 'audioStatic'
          // },
          // {
          //   title:'播报地址',
          //   align:"center",
          //   dataIndex: 'audioId'
          // },
          // {
          //   title:'是否需要前置',
          //   align:"center",
          //   dataIndex: 'isBegin'
          // },
          // {
          //   title:'前置模型类型',
          //   align:"center",
          //   dataIndex: 'beginEventTypes'
          // },
          // {
          //   title:'前置模型内容',
          //   align:"center",
          //   dataIndex: 'beginName'
          // },
          {
            title:'保存目录',
            align:"center",
            dataIndex: 'pathSave'
          },
          // {
          //   title:'是否保存图片',
          //   align:"center",
          //   dataIndex: 'savePic',
          //   customRender: (text) => (text ? filterMultiDictText(this.dictOptions['savePic'], text) : ''),
          // },
           {
            title:'是否开启报警录像',
            align:"center",
            dataIndex: 'isRecording_dictText',
          //  customRender: (text) => (text ? filterMultiDictText(this.dictOptions['push_static'], text) : ''),
          },
          {
            title:'报警录像时间',
            align:"center",
            dataIndex: 'recordTime'
          },
          {
            title:'是否本地保存录像',
            align:"center",
            dataIndex: 'saveRecord_dictText',
        //   customRender: (text) => (text ? filterMultiDictText(this.dictOptions['push_static'], text) : ''),
          },
          {
            title:'是否保存本地报警',
            align:"center",
            dataIndex: 'saveLocalhost_dictText',
          },
          // {
          //   title:'是否开启区域识别',
          //   align:"center",
          //   dataIndex: 'isBy_dictText',
          // },
          {
            title:'备注',
            align:"center",
            dataIndex: 'remake'
          },
          {
            title: '操作',
            dataIndex: 'action',
            align:"center",
            fixed:"right",
            width:147,
            scopedSlots: { customRender: 'action' }
          }
        ],
        url: {
          list: "/video/tabAiSubscriptionNew/list",
          delete: "/video/tabAiSubscriptionNew/delete",
          deleteBatch: "/video/tabAiSubscriptionNew/deleteBatch",
          exportXlsUrl: "/video/tabAiSubscriptionNew/exportXls",
          importExcelUrl: "video/tabAiSubscriptionNew/importExcel",
          updateUrl:"video/tabAiSubscriptionNew/start",
          stopUrl:"video/tabAiSubscriptionNew/stop",
           startBatch:"video/tabAiSubscriptionNew/startBatch",
        },
        dictOptions:{},
        superFieldList:[],
      }
    },
    created() {
      this.$set(this.dictOptions, 'savePic', [{text:'是',value:'Y'},{text:'否',value:'N'}])
      this.$set(this.dictOptions, 'isRecording', [{text:'是',value:'Y'},{text:'否',value:'N'}])
      this.$set(this.dictOptions, 'saveRecord', [{text:'是',value:'Y'},{text:'否',value:'N'}])
    this.getSuperFieldList();
    },
    computed: {
      importExcelUrl: function(){
        return `${window._CONFIG['domianURL']}/${this.url.importExcelUrl}`;
      },
    },
    methods: {
      handleEditStart(record){
              this.$refs.modalForm2.edit(record);
      },
      initDictConfig(){
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'string',value:'pyType',text:'解码脚本',dictCode:''})
        fieldList.push({type:'string',value:'eventTypes',text:'解码方式',dictCode:''})
        fieldList.push({type:'string',value:'eventUrl',text:'订阅回调地址',dictCode:''})
        fieldList.push({type:'string',value:'indexCode',text:'设备编号',dictCode:''})
        fieldList.push({type:'string',value:'eventNumber',text:'同类型报警间隔',dictCode:''})
        fieldList.push({type:'string',value:'eventInfo',text:'报警消息',dictCode:''})
        fieldList.push({type:'string',value:'remake',text:'备注',dictCode:''})
        fieldList.push({type:'int',value:'pushStatic',text:'推送状态',dictCode:''})
        fieldList.push({type:'int',value:'runState',text:'执行状态',dictCode:''})
        fieldList.push({type:'string',value:'name',text:'名称',dictCode:''})
        fieldList.push({type:'int',value:'audioStatic',text:'播报状态',dictCode:''})
        fieldList.push({type:'string',value:'audioId',text:'播报地址',dictCode:''})
        fieldList.push({type:'string',value:'isBegin',text:'是否需要前置',dictCode:''})
        fieldList.push({type:'string',value:'beginEventTypes',text:'前置模型类型',dictCode:''})
        fieldList.push({type:'string',value:'beginName',text:'前置模型内容',dictCode:''})
        fieldList.push({type:'string',value:'pathSave',text:'保存目录',dictCode:''})
        fieldList.push({type:'switch',value:'savePic',text:'是否保存图片'})
        fieldList.push({type:'switch',value:'isRecording',text:'是否开启报警录像'})
        fieldList.push({type:'int',value:'recordTime',text:'报价录像时间',dictCode:''})
        fieldList.push({type:'switch',value:'saveRecord',text:'是否本地保存录像'})
        this.superFieldList = fieldList
      },
      handleRun(record,flag){
        let that = this;
        this.$confirm({
          title: "确认识别吗",
          content: "手动触发后一直执行,直到手动结束！",
          onOk: function() {
      
             // debugger;
            let url=that.url.updateUrl;
            record.runState=flag;
            httpAction(url, record, "POST").then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
                  that.loadData();
            }).finally(() => {
              that.confirmLoading = false;
            })
        
          }
        });
      },
      handleStop(record,flag){
        let that = this;
        this.$confirm({
          title: "确认结束吗？",
          content: "手动触发结束，推理终止！",
          onOk: function() {
      
             // debugger;
            let url=that.url.stopUrl;
            record.runState=flag;
            httpAction(url, record, "POST").then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
                  that.loadData();
            }).finally(() => {
              that.confirmLoading = false;
            })
        
          }
        });
      },
      batchStart(flag){
        var ids = ''
        for (var a = 0; a < this.selectedRowKeys.length; a++) {
          ids += this.selectedRowKeys[a] + ','
        }
        console.log("ids",ids)
        let that = this;
        this.$confirm({
          title: "确认批量操作吗？",
          content: "批量操请谨慎使用！",
          onOk: function() {
              
             // debugger;
            let record={};
            let url=that.url.startBatch;
            record.runState=flag;
            record.id=ids;
            httpAction(url, record, "POST").then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
                  that.loadData();
            }).finally(() => {
              that.confirmLoading = false;
            })
        
          }
        });
      }
    }
  }
</script>
<style scoped>
  @import '~@assets/less/common.less';
</style>