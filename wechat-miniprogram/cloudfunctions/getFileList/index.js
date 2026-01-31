// 云函数入口文件 - getFileList
// 获取文件列表

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const wxContext = cloud.getWXContext();
  const openid = wxContext.OPENID;
  
  const { deviceId, fileType, page = 1, pageSize = 20 } = event;
  
  if (!deviceId) {
    return {
      success: false,
      message: '设备ID不能为空'
    };
  }
  
  try {
    // 验证设备属于当前用户
    const deviceRes = await db.collection('devices').where({
      deviceId: deviceId,
      boundUserId: openid
    }).get();
    
    if (deviceRes.data.length === 0) {
      return {
        success: false,
        message: '设备不存在或不属于您'
      };
    }
    
    // 构建查询条件
    const query = {
      deviceId: deviceId
    };
    
    if (fileType && (fileType === 'video' || fileType === 'photo')) {
      query.fileType = fileType;
    }
    
    // 查询文件列表
    const skip = (page - 1) * pageSize;
    
    const filesRes = await db.collection('files')
      .where(query)
      .orderBy('createTime', 'desc')
      .skip(skip)
      .limit(pageSize)
      .get();
    
    const files = filesRes.data;
    
    // 刷新临时URL（如果需要）
    const fileIds = files.filter(f => f.fileId).map(f => f.fileId);
    const thumbIds = files.filter(f => f.thumbFileId).map(f => f.thumbFileId);
    
    let urlMap = {};
    if (fileIds.length > 0 || thumbIds.length > 0) {
      try {
        const allIds = [...new Set([...fileIds, ...thumbIds])];
        const urlRes = await cloud.getTempFileURL({
          fileList: allIds
        });
        
        if (urlRes.fileList) {
          urlRes.fileList.forEach(item => {
            if (item.tempFileURL) {
              urlMap[item.fileID] = item.tempFileURL;
            }
          });
        }
      } catch (e) {
        console.log('刷新临时URL失败');
      }
    }
    
    // 处理文件数据
    const processedFiles = files.map(file => ({
      _id: file._id,
      fileId: file.fileId,
      fileName: file.fileName,
      fileType: file.fileType,
      fileSize: file.fileSize,
      duration: file.duration,
      createTime: file.createTime,
      tempFileURL: urlMap[file.fileId] || file.tempFileURL,
      thumbUrl: urlMap[file.thumbFileId] || file.thumbUrl
    }));
    
    // 获取总数
    const countRes = await db.collection('files').where(query).count();
    
    return {
      success: true,
      files: processedFiles,
      total: countRes.total,
      page: page,
      pageSize: pageSize,
      hasMore: skip + files.length < countRes.total
    };
    
  } catch (err) {
    console.error('获取文件列表失败:', err);
    return {
      success: false,
      message: '获取失败: ' + err.message
    };
  }
};
