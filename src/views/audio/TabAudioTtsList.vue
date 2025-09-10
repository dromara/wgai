<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <!-- <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
        </a-row>
      </a-form>
    </div> -->
    <!-- 查询区域-END -->

 <div class="contable">
    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAdd" type="primary" class="xz" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('文本转TTS')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" class="dr" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel"><a-icon type="delete"/>删除</a-menu-item>
        </a-menu>
        <a-button style="margin-left: 8px"> 批量操作 <a-icon type="down" /></a-button>
      </a-dropdown>
    </div>

    <!-- table区域-begin -->
    <div class="datagrid-view">
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

 <template slot="audio" slot-scope="text,record">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无文件</span>
           <audio   v-else controls :src="getImgView(record.savePath)"></audio>
        </template>

        <span slot="action" slot-scope="text, record">
          <a @click="handleEdit(record)">编辑</a>
          <a-divider type="vertical" />
          <a @click="handleTextToSpeed(record)">转文字</a>
          <a-divider type="vertical" />
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

    <tab-audio-tts-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-audio-tts-modal>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabAudioTtsModal from './modules/TabAudioTtsModal'
  import {filterMultiDictText} from '@/components/dict/JDictSelectUtil'
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  export default {
    name: 'TabAudioTtsList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabAudioTtsModal
    },
    data () {
      return {
        description: '文本转TTS管理页面',
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
            title:'语音类型',
            align:"center",
            dataIndex: 'audioType_dictText'
          },
          {
            title:'语音名称',
            align:"center",
            dataIndex: 'audioName'
          },
          {
            title:'模型文件',
            align:"center",
            dataIndex: 'audioModel',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'token文件',
            align:"center",
            dataIndex: 'audioToken',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'lexicon文件',
            align:"center",
            dataIndex: 'audioLexicon',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'Dict目录',
            align:"center",
            dataIndex: 'dictDir',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'fsts多文件地址',
            align:"center",
            dataIndex: 'ruleFasts',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'线程数',
            align:"center",
            dataIndex: 'threadNum'
          },
          {
            title:'音色下标',
            align:"center",
            dataIndex: 'audioSid'
          },
          {
            title:'语音速度',
            align:"center",
            dataIndex: 'audioSpeed'
          },
          {
            title:'转换后保存地址',
            align:"center",
            dataIndex: 'savePath',
             scopedSlots: {customRender: 'audio'},
              // customRender: function (t, r, index) {
              //       return '<audio controls :src="record.dictDir"></audio>';
              // }
          },
          {
            title:'文本转语音内容',
            align:"center",
            dataIndex: 'audioText'
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
          list: "/audio/tabAudioTts/list",
          delete: "/audio/tabAudioTts/delete",
          deleteBatch: "/audio/tabAudioTts/deleteBatch",
          exportXlsUrl: "/audio/tabAudioTts/exportXls",
          importExcelUrl: "audio/tabAudioTts/importExcel",
          textToSpeed:"audio/tabAudioTts/textToSpeed"
        },
        dictOptions:{},
        superFieldList:[],
      }
    },
    created() {
    this.getSuperFieldList();
    },
    computed: {
      importExcelUrl: function(){
        return `${window._CONFIG['domianURL']}/${this.url.importExcelUrl}`;
      },
    },
    methods: {
      initDictConfig(){
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'string',value:'audioType',text:'语音类型',dictCode:'audio_type'})
        fieldList.push({type:'string',value:'audioName',text:'语音名称',dictCode:''})
        fieldList.push({type:'string',value:'audioModel',text:'模型文件',dictCode:''})
        fieldList.push({type:'string',value:'audioToken',text:'token文件',dictCode:''})
        fieldList.push({type:'string',value:'audioLexicon',text:'lexicon文件',dictCode:''})
        fieldList.push({type:'string',value:'dictDir',text:'Dict目录',dictCode:''})
        fieldList.push({type:'string',value:'ruleFasts',text:'fsts多文件地址',dictCode:''})
        fieldList.push({type:'int',value:'threadNum',text:'线程数',dictCode:''})
        fieldList.push({type:'int',value:'audioSid',text:'音色下标',dictCode:''})
        fieldList.push({type:'double',value:'audioSpeed',text:'语音速度',dictCode:''})
        fieldList.push({type:'string',value:'savePath',text:'保存地址',dictCode:''})
        fieldList.push({type:'string',value:'audioText',text:'文本转语音内容',dictCode:''})
        this.superFieldList = fieldList
      },
      handleTextToSpeed(info){
          console.log("info", this.url);
          let that = this;
          this.$confirm({
            title: "确定文字转语音吗？",
            content: "会使用当前文字内容哦",
            onOk: function() {
              let httpurl = '';
              let method = '';
              //  debugger;
              httpurl += that.url.textToSpeed;
              method = 'post';
          
              httpAction(httpurl, info, method).then((res) => {
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
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height: calc(100vh - 337px);} */
.datagrid-view {
	height:76vh!important;
}
/deep/.ant-table {
	height:calc(74vh - 91px) !important;overflow: auto;
}
</style>