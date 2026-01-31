// pages/scan/scan.js
const app = getApp();

Page({
  data: {
    scanning: false,
    bindingDevice: null
  },

  onLoad: function() {
    // 页面加载时自动开始扫码
    this.startScan();
  },

  // 开始扫码
  startScan: function() {
    this.setData({ scanning: true });

    wx.scanCode({
      onlyFromCamera: true,
      scanType: ['qrCode'],
      success: (res) => {
        this.handleScanResult(res.result);
      },
      fail: (err) => {
        console.error('扫码失败', err);
        this.setData({ scanning: false });
        
        if (err.errMsg.indexOf('cancel') === -1) {
          wx.showToast({
            title: '扫码失败',
            icon: 'none'
          });
        }
      },
      complete: () => {
        this.setData({ scanning: false });
      }
    });
  },

  // 处理扫码结果
  handleScanResult: function(result) {
    console.log('扫码结果:', result);

    try {
      // 解析二维码数据
      const data = JSON.parse(result);
      
      if (data.type !== 'evcam_bind') {
        wx.showToast({
          title: '无效的设备二维码',
          icon: 'none'
        });
        return;
      }

      // 显示设备信息，等待用户确认
      this.setData({
        bindingDevice: {
          deviceId: data.deviceId,
          deviceName: data.deviceName || 'EVCam 设备',
          serverUrl: data.serverUrl
        }
      });

    } catch (e) {
      console.error('解析二维码失败', e);
      wx.showToast({
        title: '无效的二维码格式',
        icon: 'none'
      });
    }
  },

  // 确认绑定
  confirmBind: function() {
    if (!this.data.bindingDevice) return;

    wx.showLoading({ title: '绑定中...' });

    wx.cloud.callFunction({
      name: 'bindDevice',
      data: {
        deviceId: this.data.bindingDevice.deviceId,
        deviceName: this.data.bindingDevice.deviceName
      }
    }).then(res => {
      wx.hideLoading();

      if (res.result && res.result.success) {
        // 保存绑定信息
        app.saveBoundDevice({
          deviceId: this.data.bindingDevice.deviceId,
          deviceName: this.data.bindingDevice.deviceName,
          boundTime: Date.now()
        });

        wx.showToast({
          title: '绑定成功',
          icon: 'success'
        });

        // 返回首页
        setTimeout(() => {
          wx.navigateBack();
        }, 1500);

      } else {
        wx.showToast({
          title: res.result?.message || '绑定失败',
          icon: 'none'
        });
      }
    }).catch(err => {
      wx.hideLoading();
      console.error('绑定失败', err);
      wx.showToast({
        title: '绑定失败，请重试',
        icon: 'none'
      });
    });
  },

  // 取消绑定
  cancelBind: function() {
    this.setData({ bindingDevice: null });
  },

  // 重新扫码
  rescan: function() {
    this.setData({ bindingDevice: null });
    this.startScan();
  }
});
