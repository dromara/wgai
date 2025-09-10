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
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('区域入侵配置')">导出</a-button>
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
		<!--  <a @click="handleEdit(record)">编辑</a> -->
		  <a @click="handleEditStart(record)">修改入侵</a>
		  <a-divider type="vertical" />
		  <a @click="handleStart(record)">区域入侵识别</a>
		  
		  <a-divider type="vertical" v-if="record.spareOne=='1'" />
		
		  <a @click="handleEnd(record)" v-if="record.spareOne=='1'" >入侵结束</a>
	
   
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

    <tab-video-util-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-video-util-modal>
   <video-util class="contc" :width="1200" ref="modalForm2" @ok="modalFormOk"></video-util>
    </div>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabVideoUtilModal from './modules/TabVideoUtilModal'
  import videoUtil from './modules/videoUtil'
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  export default {
    name: 'TabVideoUtilList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabVideoUtilModal,
      videoUtil
    },
    data () {
      return {
        description: '区域入侵配置管理页面',
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
          // {
          //   title:'原始尺寸',
          //   align:"center",
          //   dataIndex: 'videoStart'
          // },
          // {
          //   title:'原始X坐标',
          //   align:"center",
          //   dataIndex: 'videoStartx'
          // },
          // {
          //   title:'原始y坐标',
          //   align:"center",
          //   dataIndex: 'videoStarty'
          // },
          // {
          //   title:'结束坐标x',
          //   align:"center",
          //   dataIndex: 'videoEndx'
          // },
          // {
          //   title:'结束坐标y',
          //   align:"center",
          //   dataIndex: 'videoEndy'
          // },
           {
            title:'入侵内容',
            align:"center",
            dataIndex: 'videoJson'
          },
          {
            title:'实际尺寸',
            align:"center",
            dataIndex: 'canvasStart'
          },
          {
            title:'实际X坐标',
            align:"center",
            dataIndex: 'canvasStartx'
          },
          {
            title:'实际y坐标',
            align:"center",
            dataIndex: 'canvasStarty'
          },
          {
            title:'实际宽度',
            align:"center",
            dataIndex: 'canvasWidth'
          },
          {
            title:'实际高度',
            align:"center",
            dataIndex: 'canvasHeight'
          },
          {
            title:'其他内容',
            align:"center",
            dataIndex: 'canvasJson'
          },
          {
            title:'备注',
            align:"center",
            dataIndex: 'remerk'
          },
          {
            title:'是否识别',
            align:"center",
            dataIndex: 'spareOne'
          },
          // {
          //   title:'视频id',
          //   align:"center",
          //   dataIndex: 'videoId',
          //   hidden:true
          // },
          {
            title:'视频名称',
            align:"center",
            dataIndex: 'videoId_dictText'
          },
          // {
          //   title:'模型选择',
          //   align:"center",
          //   dataIndex: 'spareTwo'
          // },
          {
            title:'模型选择',
            align:"center",
            dataIndex: 'spareTwo_dictText',
                  hidden:true
          },
          // {
          //   title:'备注3',
          //   align:"center",
          //   dataIndex: 'spateThree'
          // },
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
          list: "/video/tabVideoUtil/list",
          delete: "/video/tabVideoUtil/delete",
          deleteBatch: "/video/tabVideoUtil/deleteBatch",
          exportXlsUrl: "/video/tabVideoUtil/exportXls",
          importExcelUrl: "video/tabVideoUtil/importExcel",
          
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
        fieldList.push({type:'string',value:'videoStart',text:'原始尺寸',dictCode:''})
        fieldList.push({type:'string',value:'videoStartx',text:'原始X坐标',dictCode:''})
        fieldList.push({type:'string',value:'videoStarty',text:'原始y坐标',dictCode:''})
        fieldList.push({type:'string',value:'videoEndx',text:'结束坐标x',dictCode:''})
        fieldList.push({type:'string',value:'videoEndy',text:'结束坐标y',dictCode:''})
        fieldList.push({type:'string',value:'videoJson',text:'其他内容',dictCode:''})
        fieldList.push({type:'string',value:'canvasStart',text:'实际尺寸',dictCode:''})
        fieldList.push({type:'string',value:'canvasStartx',text:'实际X坐标',dictCode:''})
        fieldList.push({type:'string',value:'canvasStarty',text:'实际y坐标',dictCode:''})
        fieldList.push({type:'string',value:'canvasWidth',text:'实际宽度',dictCode:''})
        fieldList.push({type:'string',value:'canvasHeight',text:'实际高度',dictCode:''})
        fieldList.push({type:'string',value:'canvasJson',text:'其他内容',dictCode:''})
        fieldList.push({type:'string',value:'remerk',text:'remerk',dictCode:''})
        fieldList.push({type:'string',value:'spareOne',text:'备注',dictCode:''})
        fieldList.push({type:'string',value:'videoId',text:'视频id',dictCode:''})
        fieldList.push({type:'string',value:'videoName',text:'视频名称',dictCode:''})
        fieldList.push({type:'string',value:'spareTwo',text:'备注2',dictCode:''})
        fieldList.push({type:'string',value:'spateThree',text:'备注3',dictCode:''})
        this.superFieldList = fieldList
      },
      handleStart(info){ //开始监听区域入侵
        let that = this;
        this.$confirm({
          title: "确认开始监听区域入侵吗",
          content: "确认开始监听区域入侵吗?视频会识别到手动结束",
          onOk: function() {
            let httpurl = '';
            let method = '';
            //  debugger;
            httpurl += "/video/tabVideoUtil/startVideoUtil";
            method = 'post';
        
            httpAction(httpurl, info, method).then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
               that.searchReset();
            }).finally(() => {
                that.confirmLoading = false;
                
            })
        
          }
        });
      },
      handleEnd(info){//结束监听区域入侵
        let that = this;
        this.$confirm({
          title: "确认结束吗",
          content: "结束区域入侵识别内容",
          onOk: function() {
            let httpurl = '';
            let method = '';
            //  debugger;
            httpurl += "/video/tabVideoUtil/stopVideoUtil";
            method = 'post';
        
            httpAction(httpurl, info, method).then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
              that.searchReset();
            }).finally(() => {
              that.confirmLoading = false;
     
            })
        
          }
        });
      },
      handleEditStart(record){
              this.$refs.modalForm2.edit(record);
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