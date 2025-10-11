<template>
  <a-card :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="订阅名称">
          <j-search-select-tag  v-model="queryParam.subId" dict="tab_ai_subscription_new,name,id" placeholder="请选择订阅ID" />
      
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="后置模型">
                <j-search-select-tag  v-model="queryParam.nextMode" dict="tab_ai_model,ai_name,id" placeholder="请选择后置模型" />
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
      <a-button type="primary" icon="download" @click="handleExportXls('AI视频配置')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <j-super-query :fieldList="superFieldList" ref="superQueryModal" @handleSuperQuery="handleSuperQuery"></j-super-query>
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel"><a-icon type="delete"/>删除</a-menu-item>
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

          <a-divider type="vertical" />
          <a @click="handleEditStart(record)">设置边界</a>
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

    <tab-ai-video-setting-modal ref="modalForm" @ok="modalFormOk"></tab-ai-video-setting-modal>
    <pic-util class="contc" :width="1200" ref="modalForm2" @ok="modalFormOk"></pic-util>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabAiVideoSettingModal from './modules/TabAiVideoSettingModal'
  import picUtil from './modules/picUtilSetting'
  export default {
    name: 'TabAiVideoSettingList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabAiVideoSettingModal,picUtil
    },
    data () {
      return {
        description: 'AI视频配置管理页面',
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
            dataIndex: 'subId_dictText'
          },
          {
            title:'识别类型',
            align:"center",
            dataIndex: 'difyType_dictText'
          },
          {
            title:'是否需要前置',
            align:"center",
            dataIndex: 'isBefor_dictText'
          },
         
          {
            title:'前置模型',
            align:"center",
            dataIndex: 'modelId_dictText'
          },
          {
            title:'前置识别内容',
            align:"center",
            dataIndex: 'modelTxt'
          },
          {
            title:'后置模型',
            align:"center",
            dataIndex: 'nextMode_dictText'
          },
          {
            title:'是否跟随前置坐标',
            align:"center",
            dataIndex: 'isFollow_dictText'
          },
     
          {
            title:'跟随最大距离',
            align:"center",
            dataIndex: 'followPosition'
          },
          {
            title:'是否跟随前置放大',
            align:"center",
            dataIndex: 'isBeforZoom_dictText'
          },
          {
            title:'是否识别报警',
            align:"center",
            dataIndex: 'warinngMethod_dictText'
          },
          {
            title:'是否开启区域识别',
            align:"center",
            dataIndex: 'isBy_dictText',
          },
          {
            title:'未识别到预警文本',
            align:"center",
            dataIndex: 'noDifText'
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
          list: "/video/tabAiVideoSetting/list",
          delete: "/video/tabAiVideoSetting/delete",
          deleteBatch: "/video/tabAiVideoSetting/deleteBatch",
          exportXlsUrl: "/video/tabAiVideoSetting/exportXls",
          importExcelUrl: "video/tabAiVideoSetting/importExcel",
          
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
      handleEditStart(record){
              this.$refs.modalForm2.edit(record);
      },
      initDictConfig(){
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'string',value:'subId',text:'订阅ID',dictCode:''})
        fieldList.push({type:'string',value:'isBefor',text:'是否需要前置',dictCode:''})
        fieldList.push({type:'string',value:'modelId',text:'前置模型',dictCode:''})
        fieldList.push({type:'string',value:'modelTxt',text:'前置识别内容',dictCode:''})
        fieldList.push({type:'string',value:'nextMode',text:'后置模型',dictCode:''})
        fieldList.push({type:'string',value:'spareOne',text:'备用1',dictCode:''})
        this.superFieldList = fieldList
      }
    }
  }
</script>
<style scoped>
  @import '~@assets/less/common.less';
</style>