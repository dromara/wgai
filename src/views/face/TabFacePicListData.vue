<template>
  <a-card :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="人脸名称">
              <a-input placeholder="请输入人脸名称" v-model="queryParam.faceName"></a-input>
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <a-form-item label="是否标注">

                       <j-dict-select-tag type="list" v-model="queryParam.isRun" dictCode="push_static" placeholder="请选择是否标注" />
            </a-form-item>
          </a-col>
          <a-col :xl="6" :lg="7" :md="8" :sm="24">
            <span style="float: left;overflow: hidden;" class="table-page-search-submitButtons">
              <a-button type="primary" @click="searchQuery" icon="search">查询</a-button>
              <a-button type="primary" @click="searchReset" icon="reload" style="margin-left: 8px">重置</a-button>
              <a @click="handleToggleSearch" style="margin-left: 8px">
                {{ toggleSearchStatus ? '收起' : '展开' }}
                <a-icon :type="toggleSearchStatus ? 'up' : 'down'"/>
              </a>
            </span>
          </a-col>
        </a-row>
      </a-form>
    </div>
    <!-- 查询区域-END -->

    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAddMore" type="primary" icon="plus">批量添加</a-button>
      
      <a-button @click="handleAdd" type="primary" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" @click="handleExportXls('人脸图片库')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <j-super-query :fieldList="superFieldList" ref="superQueryModal" @handleSuperQuery="handleSuperQuery"></j-super-query>
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel"><a-icon type="delete"/>删除</a-menu-item>
          <a-menu-item key="2" @click="dobatch(0,'')"><a-icon type="delete"/>批量训练</a-menu-item>
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
          <a @click="dobatch(1,record.id)">采集数据</a>
        
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

    <tab-face-pic-modal ref="modalForm" @ok="modalFormOk"></tab-face-pic-modal>
    <tab-face-pic-batch-modal ref="batchModalForm" @ok="modalFormOk"></tab-face-pic-batch-modal>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabFacePicModal from './modules/TabFacePicModal'
  import TabFacePicBatchModal from './modules/TabFacePicBatchModal'
  import {
    httpAction,
    getAction
  } from '@/api/manage'
  export default {
    name: 'TabFacePicList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabFacePicModal,
      TabFacePicBatchModal  
    },
    data () {
      return {
        description: '人脸图片库管理页面',
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
          //             title:'所属模型',
          //             align:"center",
          //             dataIndex: 'modelId_dictText'
          // },
          {
            title:'人脸名称',
            align:"center",
            dataIndex: 'faceName'
          },
          // {
          //   title:'人脸图片',
          //   align:"center",
          //   dataIndex: 'facePic',
          //   scopedSlots: {customRender: 'imgSlot'}
          // },
          {
            title:'512维度数据',
            align:"center",
            dataIndex: 'face512'
          },
          {
            title:'3D维度数据',
            align:"center",
            dataIndex: 'face3d'
          },
          {
            title:'其他维度数据',
            align:"center",
            dataIndex: 'faceOther'
          },
          {
            title:'是否采集',
            align:"center",
            dataIndex: 'isRun_dictText'
          },
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
          list: "/face/tabFacePic/list",
          delete: "/face/tabFacePic/delete",
          deleteBatch: "/face/tabFacePic/deleteBatch",
          exportXlsUrl: "/face/tabFacePic/exportXls",
          importExcelUrl: "face/tabFacePic/importExcel",
          doBatch:"/face/tabFacePic/trainBatch"
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
      handleAddMore() {
        this.$refs.batchModalForm.show();
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'string',value:'faceName',text:'人脸名称',dictCode:''})
        fieldList.push({type:'string',value:'facePic',text:'人脸图片',dictCode:''})
        fieldList.push({type:'Text',value:'face512',text:'512维度数据',dictCode:''})
        fieldList.push({type:'Text',value:'face3d',text:'3D维度数据',dictCode:''})
        fieldList.push({type:'string',value:'faceOther',text:'其他维度数据',dictCode:''})
        fieldList.push({type:'string',value:'isRun',text:'是否标注',dictCode:''})
        fieldList.push({type:'string',value:'remake',text:'备注',dictCode:''})
        this.superFieldList = fieldList
      },
      dobatch(type,id){
          var ids = '';
        if(type==0){ //多选
          for (var a = 0; a < this.selectedRowKeys.length; a++) {
            ids += this.selectedRowKeys[a] + ',';
          }
        }else{ //单点
          ids=id+",";
        }
      
       
        console.log("ids",ids)
        let that = this;
        this.$confirm({
          title: "确认采集人脸吗？",
          content: "采集数据会在后台执行！",
          onOk: function() {
              
             // debugger;
            // let record={};
            // let url=that.url.doBatch;
            // record.ids=ids;
            httpAction(that.url.doBatch+"?ids="+ids, "", "POST").then((res) => {
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