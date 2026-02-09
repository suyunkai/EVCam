package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * CarSignalManager 转向灯观察者（基于吉利L6/L7真实API）
 * 
 * 核心方法：getIndcrSts()
 * 返回值：0=关闭, 1=左转, 2=右转, 3=双闪
 * 
 * 初始化方式：
 * 1. ECARX API: ecarxcar_service → ECarXCar.createCar() → getCarManager("car_signal")
 * 2. CarSensor API: CarSensor.create() (备用)
 */
public class CarSignalManagerObserver {
    
    private static final String TAG = "CarSignalManagerObserver";
    private static final long POLL_INTERVAL_MS = 200; // 200ms轮询一次
    
    /**
     * 转向灯信号回调接口
     */
    public interface TurnSignalListener {
        /** 转向灯状态变化 */
        void onTurnSignal(String direction, boolean on);
        /** 连接状态变化 */
        void onConnectionStateChanged(boolean connected);
    }
    
    private final Context context;
    private final TurnSignalListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private Object carSignalManager = null;
    private Method getIndcrStsMethod = null;  // 获取转向灯状态的方法
    
    private volatile boolean running = false;
    private volatile boolean connected = false;
    
    // 上一次的转向灯状态（0=关闭, 1=左转, 2=右转, 3=双闪）
    private int lastTurnSignalState = 0;
    
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            
            try {
                pollTurnSignalState();
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to poll turn signal state", e);
            } finally {
                if (running) {
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                }
            }
        }
    };
    
    public CarSignalManagerObserver(Context context, TurnSignalListener listener) {
        this.context = context;
        this.listener = listener;
    }
    
    /**
     * 启动监听
     */
    public void start() {
        if (running) return;
        running = true;
        lastTurnSignalState = -1; // 重置状态，确保首次读取会触发回调
        
        new Thread(() -> {
            boolean success = initCarSignalManager();
            
            if (listener != null) {
                handler.post(() -> listener.onConnectionStateChanged(success));
            }
            
            if (success) {
                handler.post(pollRunnable);
            }
        }).start();
    }
    
    /**
     * 停止监听
     */
    public void stop() {
        running = false;
        connected = false;
        handler.removeCallbacks(pollRunnable);
        carSignalManager = null;
        getIndcrStsMethod = null;
    }
    
    /**
     * 当前是否已连接
     */
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * 一次性连接测试（用于 UI 状态检查）
     */
    public static boolean testConnection(Context context) {
        try {
            // 方法1：尝试 ECARX API
            try {
                Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
                Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
                Object binder = getServiceMethod.invoke(null, "ecarxcar_service");
                
                if (binder != null) {
                    Class<?> stubClass = Class.forName("ecarx.car.IECarXCar$Stub");
                    Method asInterfaceMethod = stubClass.getMethod("asInterface", Class.forName("android.os.IBinder"));
                    Object eCarXCar = asInterfaceMethod.invoke(null, binder);
                    
                    if (eCarXCar != null) {
                        Class<?> eCarXCarClass = Class.forName("ecarx.car.ECarXCar");
                        Class<?> iECarXCarClass = Class.forName("ecarx.car.IECarXCar");
                        Method createCarMethod = eCarXCarClass.getMethod("createCar", Context.class, iECarXCarClass);
                        Object car = createCarMethod.invoke(null, context, eCarXCar);
                        
                        if (car != null) {
                            Method getCarManagerMethod = car.getClass().getMethod("getCarManager", String.class, iECarXCarClass);
                            Object carSignalManager = getCarManagerMethod.invoke(car, "car_signal", eCarXCar);
                            
                            if (carSignalManager != null) {
                                Method method = carSignalManager.getClass().getMethod("getIndcrSts");
                                Object result = method.invoke(carSignalManager);
                                AppLog.d(TAG, "✅ ECARX CarSignalManager 可用，当前转向灯状态: " + result);
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.d(TAG, "ECARX API 不可用: " + e.getMessage());
            }
            
            // 方法2：尝试 CarSensor API (备用)
            try {
                Class<?> clazz = Class.forName("com.ecarx.xui.adaptapi.car.sensor.CarSensor");
                Method createMethod = clazz.getMethod("create", Context.class);
                Object carSensor = createMethod.invoke(null, context);
                
                if (carSensor != null) {
                    Method method = carSensor.getClass().getMethod("getIndcrSts");
                    Object result = method.invoke(carSensor);
                    AppLog.d(TAG, "✅ CarSensor API 可用，当前转向灯状态: " + result);
                    return true;
                }
            } catch (Exception e) {
                AppLog.d(TAG, "CarSensor API 不可用: " + e.getMessage());
            }
            
            AppLog.e(TAG, "❌ 所有 Car API 均不可用");
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "CarSignalManager test failed: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== Internal ====================
    
    /**
     * 初始化 CarSignalManager（参考 L7Test 项目的成功实现）
     */
    private boolean initCarSignalManager() {
        try {
            AppLog.d(TAG, "🔍 开始初始化 CarSignalManager...");
            
            // 方法1：尝试通过 ServiceManager 获取 ecarxcar_service
            try {
                Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
                Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
                Object binder = getServiceMethod.invoke(null, "ecarxcar_service");
                
                if (binder != null) {
                    AppLog.d(TAG, "✅ ecarxcar_service Binder获取成功");
                    Class<?> stubClass = Class.forName("ecarx.car.IECarXCar$Stub");
                    Method asInterfaceMethod = stubClass.getMethod("asInterface", Class.forName("android.os.IBinder"));
                    Object eCarXCar = asInterfaceMethod.invoke(null, binder);
                    
                    if (eCarXCar != null) {
                        Class<?> eCarXCarClass = Class.forName("ecarx.car.ECarXCar");
                        Class<?> iECarXCarClass = Class.forName("ecarx.car.IECarXCar");
                        Method createCarMethod = eCarXCarClass.getMethod("createCar", Context.class, iECarXCarClass);
                        Object car = createCarMethod.invoke(null, context, eCarXCar);
                        
                        if (car != null) {
                            Method getCarManagerMethod = car.getClass().getMethod("getCarManager", String.class, iECarXCarClass);
                            carSignalManager = getCarManagerMethod.invoke(car, "car_signal", eCarXCar);
                            
                            if (carSignalManager != null) {
                                AppLog.d(TAG, "✅ ECARX CarSignalManager 初始化成功");
                                getIndcrStsMethod = carSignalManager.getClass().getMethod("getIndcrSts");
                                
                                // 测试调用
                                Object testResult = getIndcrStsMethod.invoke(carSignalManager);
                                AppLog.d(TAG, "📊 当前转向灯状态: " + testResult);
                                
                                connected = true;
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.w(TAG, "ECARX API 初始化失败: " + e.getMessage());
            }
            
            // 方法2：尝试 CarSensor API (备用)
            try {
                AppLog.d(TAG, "尝试备用 CarSensor API...");
                Class<?> clazz = Class.forName("com.ecarx.xui.adaptapi.car.sensor.CarSensor");
                Method createMethod = clazz.getMethod("create", Context.class);
                carSignalManager = createMethod.invoke(null, context);
                
                if (carSignalManager != null) {
                    AppLog.d(TAG, "✅ CarSensor 初始化成功(备用API)");
                    getIndcrStsMethod = carSignalManager.getClass().getMethod("getIndcrSts");
                    
                    // 测试调用
                    Object testResult = getIndcrStsMethod.invoke(carSignalManager);
                    AppLog.d(TAG, "📊 当前转向灯状态: " + testResult);
                    
                    connected = true;
                    return true;
                }
            } catch (Exception e) {
                AppLog.w(TAG, "CarSensor API 初始化失败: " + e.getMessage());
            }
            
            AppLog.e(TAG, "❌ 所有 Car API 初始化失败");
            return false;
            
        } catch (Exception e) {
            AppLog.e(TAG, "❌ CarSignalManager 初始化异常", e);
            carSignalManager = null;
            getIndcrStsMethod = null;
            connected = false;
            return false;
        }
    }
    
    /**
     * 轮询转向灯状态（200ms间隔）
     */
    private void pollTurnSignalState() {
        if (carSignalManager == null || getIndcrStsMethod == null) {
            return;
        }
        
        try {
            // 调用 getIndcrSts() 获取转向灯状态
            // 返回值：0=关闭, 1=左转, 2=右转, 3=双闪
            Object result = getIndcrStsMethod.invoke(carSignalManager);
            
            if (result != null) {
                int currentState = Integer.parseInt(result.toString());
                checkTurnSignalChange(currentState);
            } else {
                AppLog.w(TAG, "⚠️ getIndcrSts() 返回 null");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "❌ 转向灯状态读取失败: " + e.getMessage());
        }
    }
    
    /**
     * 检测转向灯状态变化并通知监听器
     * @param currentState 当前状态: 0=关闭, 1=左转, 2=右转, 3=双闪
     */
    private void checkTurnSignalChange(int currentState) {
        if (lastTurnSignalState != currentState) {
            String statusDesc = getTurnSignalDesc(currentState);
            AppLog.d(TAG, "🔄 转向灯状态变化: " + lastTurnSignalState + " → " + currentState + " (" + statusDesc + ")");
            
            // 通知监听器
            if (listener != null) {
                // 根据状态转换为方向和开关信息
                switch (currentState) {
                    case 0: // 关闭
                        // 只在从非关闭状态切换到关闭状态时，才通知关闭
                        // 避免重复触发 startHideTimer()
                        if (lastTurnSignalState == 1) {
                            // 从左转切换到关闭
                            handler.post(() -> listener.onTurnSignal("left", false));
                        } else if (lastTurnSignalState == 2) {
                            // 从右转切换到关闭
                            handler.post(() -> listener.onTurnSignal("right", false));
                        } else if (lastTurnSignalState == 3) {
                            // 从双闪切换到关闭
                            handler.post(() -> {
                                listener.onTurnSignal("left", false);
                                listener.onTurnSignal("right", false);
                            });
                        }
                        break;
                        
                    case 1: // 左转
                        handler.post(() -> listener.onTurnSignal("left", true));
                        break;
                        
                    case 2: // 右转
                        handler.post(() -> listener.onTurnSignal("right", true));
                        break;
                        
                    case 3: // 双闪
                        handler.post(() -> {
                            listener.onTurnSignal("left", true);
                            listener.onTurnSignal("right", true);
                        });
                        break;
                }
            }
            
            lastTurnSignalState = currentState;
        }
    }
    
    /**
     * 获取转向灯状态描述
     */
    private String getTurnSignalDesc(int status) {
        switch (status) {
            case 0: return "关闭";
            case 1: return "左转";
            case 2: return "右转";
            case 3: return "双闪";
            default: return "未知(" + status + ")";
        }
    }
}
