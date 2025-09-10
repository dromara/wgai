<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="所属模型">
              <j-search-select-tag placeholder="请选择所属模型" v-model="queryParam.modelId" dict="tab_model_try,model_title,id"/>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="视频类型">
              <j-dict-select-tag placeholder="请选择视频类型" v-model="queryParam.videoType" dictCode="video_type"/>
            </a-form-item>
          </a-col>
          <template v-if="toggleSearchStatus">
            <a-col :xl="6" :lg="7" :md="8" :sm="24">
              <a-form-item label="保存目录(不勾选图片模型库生效)">
                <a-input placeholder="请输入保存目录(不勾选图片模型库生效)" v-model="queryParam.savePath"></a-input>
              </a-form-item>
            </a-col>
          </template>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
              <a-button type="primary" class="cx" @click="searchQuery" icon="search">查询</a-button>
              <a-button type="primary" class="cz" @click="searchReset" icon="reload" style="margin-left: 8px">重置</a-button>
            </span>
          </a-col>
        </a-row>
      </a-form>
    </div>
    <!-- 查询区域-END -->

 <div class="contable">
    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAdd" type="primary" class="xz" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('采集图片配置')">导出</a-button>
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

        <span slot="action" slot-scope="text, record">
		  <a @click="handleEdit(record)">编辑</a>

		  <a-divider type="vertical" />
		  <a @click="handleCollect(record)">开始采集</a>
		  
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

    <tab-ai-clickpic-setting-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-ai-clickpic-setting-modal>
    </div>

  </a-card>
</template>

<script>
  import {httpAction, getAction} from '@/api/manage'
  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabAiClickpicSettingModal from './modules/TabAiClickpicSettingModal'
  import {filterMultiDictText} from '@/components/dict/JDictSelectUtil'

  export default {
    name: 'TabAiClickpicSettingList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabAiClickpicSettingModal
    },
    data () {
      return {
        description: '采集图片配置管理页面',
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
            title:'所属模型',
            align:"center",
            dataIndex: 'modelId_dictText'
          },
          {
            title:'视频类型',
            align:"center",
            dataIndex: 'videoType_dictText'
          },
          {
            title:'视频地址',
            align:"center",
            dataIndex: 'videoUrl',
            scopedSlots: {customRender: 'fileSlot'}
          },
          {
            title:'间隔帧',
            align:"center",
            dataIndex: 'interFrameInterval'
          },
          {
            title:'采集数量',
            align:"center",
            dataIndex: 'picNumber'
          },
          {
            title:'保存目录(不勾选图片模型库生效)',
            align:"center",
            dataIndex: 'savePath'
          },
          {
            title:'运行状态',
            align:"center",
            dataIndex: 'runState'
          },
          {
            title:'备注',
            align:"center",
            dataIndex: 'remake'
          },
          {
            title:'是否覆盖',
            align:"center",
            dataIndex: 'isCover',
            customRender: (text) => (text ? filterMultiDictText(this.dictOptions['isCover'], text) : ''),
          },
          {
            title:'是否放入图片模型库',
            align:"center",
            dataIndex: 'picModelInster',
            customRender: (text) => (text ? filterMultiDictText(this.dictOptions['picModelInster'], text) : ''),
          },
          {
            title: '操作',
            dataIndex: 'action',
            align:"center",
            fixed:"right",
            width:200,
            scopedSlots: { customRender: 'action' }
          }
        ],
        url: {
          list: "/video/tabAiClickpicSetting/list",
          delete: "/video/tabAiClickpicSetting/delete",
          deleteBatch: "/video/tabAiClickpicSetting/deleteBatch",
          exportXlsUrl: "/video/tabAiClickpicSetting/exportXls",
          importExcelUrl: "video/tabAiClickpicSetting/importExcel",
            starupPic: "video/tabAiClickpicSetting/starupPic",
        },
        dictOptions:{},
        superFieldList:[],
      }
    },
    created() {
      this.$set(this.dictOptions, 'isCover', [{text:'是',value:'Y'},{text:'否',value:'N'}])
      this.$set(this.dictOptions, 'picModelInster', [{text:'是',value:'Y'},{text:'否',value:'N'}])
    this.getSuperFieldList();
    },
    computed: {
      importExcelUrl: function(){
        return `${window._CONFIG['domianURL']}/${this.url.importExcelUrl}`;
      },
    },
    methods: {
      handleCollect(info){
        console.log("info", this.url);
        let that = this;
        this.$confirm({
          title: "确认采集吗",
          content: "采集后图片会存放在指定目录！",
          onOk: function() {
            let httpurl = '';
            let method = '';
            //  debugger;
            httpurl += that.url.starupPic;
            method = 'post';
        
            httpAction(httpurl, info, method).then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
         
            }).finally(() => {
              that.confirmLoading = false;
                 that.loadData();
            })
        
          }
        });
      },
      initDictConfig(){
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'sel_search',value:'modelId',text:'所属模型',dictTable:"tab_model_try", dictText:'model_title', dictCode:'id'})
        fieldList.push({type:'string',value:'videoType',text:'视频类型',dictCode:'video_type'})
        fieldList.push({type:'string',value:'videoUrl',text:'视频地址',dictCode:''})
        fieldList.push({type:'int',value:'interFrameInterval',text:'间隔帧',dictCode:''})
        fieldList.push({type:'int',value:'picNumber',text:'采集数量',dictCode:''})
        fieldList.push({type:'string',value:'savePath',text:'保存目录(不勾选图片模型库生效)',dictCode:''})
        fieldList.push({type:'string',value:'runState',text:'运行状态',dictCode:'is_open'})
        fieldList.push({type:'string',value:'remake',text:'备注',dictCode:''})
        fieldList.push({type:'switch',value:'isCover',text:'是否覆盖'})
        fieldList.push({type:'switch',value:'picModelInster',text:'是否放入图片模型库'})
        this.superFieldList = fieldList
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height:calc(100vh - 431px);} */
.datagrid-view {
	height:66vh!important;
}
/deep/.ant-table {
	height:calc(64vh - 90px) !important;overflow: auto;
}
</style>