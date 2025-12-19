<template>
  <div class="digital-human-wrapper">
    <!-- 左侧控制面板 -->
    <div class="control-panel">
      <div class="panel-header">
        <h2>🎭 数字人控制台</h2>
      </div>

      <div class="panel-content">
        <!-- 骨骼调试信息 -->
        <div class="form-group debug-box">
          <label>🦴 骨骼检测</label>
          <div class="debug-content">
            <div class="debug-item" v-for="(bone, name) in foundBones" :key="name">
              <span class="bone-name">{{ name }}</span>
              <span class="bone-status" :class="bone ? 'found' : 'missing'">
                {{ bone ? '✓' : '✗' }}
              </span>
            </div>
          </div>
        </div>

        <!-- 背景选择 -->
        <div class="form-group">
          <label>🎨 背景主题</label>
          <select v-model="background" @change="changeBackground">
            <option value="gradient1">渐变紫</option>
            <option value="gradient2">渐变蓝</option>
            <option value="gradient3">渐变粉</option>
            <option value="white">纯白色</option>
            <option value="dark">深色</option>
          </select>
        </div>

        <!-- 动作选择 -->
        <div class="form-group">
          <label>💃 姿态动作</label>
          <select v-model="proceduralAnimation" @change="changeProceduralAnimation">
            <option value="idle">自然呼吸</option>
            <option value="presenting">讲解姿态</option>
            <option value="thinking">思考</option>
            <option value="greeting">打招呼</option>
            <option value="nodding">点头</option>
          </select>
        </div>

        <!-- 口型同步开关 -->
        <div class="form-group">
          <label>👄 口型同步</label>
          <div class="switch-group">
            <label class="switch">
              <input type="checkbox" v-model="enableLipSync">
              <span class="switch-slider"></span>
            </label>
            <span>{{ enableLipSync ? '开启' : '关闭' }}</span>
          </div>
        </div>

        <!-- 眨眼开关 -->
        <div class="form-group">
          <label>👁️ 自动眨眼</label>
          <div class="switch-group">
            <label class="switch">
              <input type="checkbox" v-model="enableBlink">
              <span class="switch-slider"></span>
            </label>
            <span>{{ enableBlink ? '开启' : '关闭' }}</span>
          </div>
        </div>

        <!-- 动作强度 -->
        <div class="form-group">
          <label>🎚️ 动作强度</label>
          <input 
            type="range" 
            v-model="animationIntensity" 
            min="0.1" 
            max="2" 
            step="0.1"
            class="slider">
          <span class="slider-value">{{ animationIntensity }}x</span>
        </div>

        <!-- 讲解文本 -->
        <div class="form-group">
          <label>📝 讲解内容</label>
          <textarea 
            v-model="speechText" 
            rows="4"
            placeholder="输入要讲解的内容，支持中文语音播报..."></textarea>
        </div>

        <!-- 语音设置 -->
        <div class="form-group">
          <label>🔊 语速调节</label>
          <input 
            type="range" 
            v-model="speechRate" 
            min="0.5" 
            max="2" 
            step="0.1"
            class="slider">
          <span class="slider-value">{{ speechRate }}x</span>
        </div>

        <!-- 按钮组 -->
        <div class="button-group">
          <button @click="startSpeak" :disabled="isSpeaking" class="btn-primary">
            <span v-if="!isSpeaking">▶ 开始讲解</span>
            <span v-else>🔄 讲解中...</span>
          </button>
          <button @click="stopSpeak" :disabled="!isSpeaking" class="btn-danger">
            ⏸ 停止
          </button>
        </div>

        <!-- 状态显示 -->
        <div class="status-bar" :class="statusClass">
          <div class="status-icon">{{ statusIcon }}</div>
          <div class="status-text">{{ statusText }}</div>
        </div>
      </div>
    </div>

    <!-- 右侧3D展示区域 -->
    <div class="avatar-container">
      <div ref="avatarCanvas" class="avatar-canvas"></div>
      
      <!-- 模型信息 -->
      <div class="model-info" v-if="!isLoading">
        <div class="info-item">
          <span class="label">状态:</span>
          <span class="value success">{{ isSpeaking ? '🗣️ 讲解中' : '✓ 就绪' }}</span>
        </div>
        <div class="info-item">
          <span class="label">姿态:</span>
          <span class="value">{{ animationLabels[proceduralAnimation] }}</span>
        </div>
      </div>
    </div>

    <!-- 加载提示 -->
    <div v-if="isLoading" class="loading-mask">
      <div class="loading-content">
        <div class="spinner"></div>
        <p class="loading-text">{{ loadingText }}</p>
        <div class="progress-bar">
          <div class="progress-fill" :style="{width: loadingProgress + '%'}"></div>
        </div>
        <p class="progress-text">{{ loadingProgress }}%</p>
      </div>
    </div>
  </div>
</template>

<script>
import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader';

export default {
  name: 'DigitalHuman',
  
  data() {
    return {
      // 3D相关
      scene: null,
      camera: null,
      renderer: null,
      avatar: null,
      mixer: null,
      clock: null,
      animations: {},
      bones: {},
      morphTargets: null,
      
      // 找到的骨骼
      foundBones: {
        'Head': null,
        'Neck': null,
        'Spine': null,
        'LeftArm': null,
        'RightArm': null,
        'LeftHand': null,
        'RightHand': null,
        'LeftEye': null,
        'RightEye': null,
        'Jaw': null
      },
      
      // 程序化动画
      proceduralAnimation: 'idle',
      animationIntensity: 1.0,
      animationTime: 0,
      enableLipSync: true,
      enableBlink: true,
      lipSyncIntensity: 0,
      blinkTimer: 0,
      nextBlinkTime: 3,
      
      // UI状态
      background: 'gradient1',
      speechText: '大家好！我是智能数字人讲解助手。我可以通过口型同步和肢体语言，为您提供生动的内容讲解。让我们开始吧！',
      speechRate: 0.9,
      isSpeaking: false,
      isLoading: true,
      loadingText: '正在加载数字人模型...',
      loadingProgress: 0,
      statusText: '系统初始化中',
      statusClass: 'info',
      statusIcon: 'ℹ️',
      
      // 动作标签
      animationLabels: {
        'idle': '自然呼吸',
        'presenting': '讲解姿态',
        'thinking': '思考',
        'greeting': '打招呼',
        'nodding': '点头'
      },
      
      // 模型URL
      modelUrl: '../../../static/model/3.glb'
    };
  },
  
  mounted() {
    this.init3D();
  },
  
  beforeDestroy() {
    this.cleanup();
  },
  
  methods: {
    // 初始化3D场景
    init3D() {
      const container = this.$refs.avatarCanvas;
      const width = container.clientWidth;
      const height = container.clientHeight;

      this.scene = new THREE.Scene();
      this.camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 1000);
      this.camera.position.set(0, 1.5, 3);
      this.camera.lookAt(0, 1, 0);

      this.renderer = new THREE.WebGLRenderer({
        antialias: true,
        alpha: false
      });
      this.renderer.setSize(width, height);
      this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
      this.renderer.shadowMap.enabled = true;
      this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
      this.renderer.outputEncoding = THREE.sRGBEncoding;
      this.renderer.setClearColor(0xf0f0f0);
      container.appendChild(this.renderer.domElement);

      this.addLights();
      this.changeBackground();
      this.loadModel();
      this.clock = new THREE.Clock();
      this.animate();

      window.addEventListener('resize', this.onResize);
    },

    addLights() {
      const ambient = new THREE.AmbientLight(0xffffff, 0.6);
      this.scene.add(ambient);

      const directional = new THREE.DirectionalLight(0xffffff, 0.8);
      directional.position.set(5, 5, 5);
      directional.castShadow = true;
      this.scene.add(directional);

      const fill1 = new THREE.DirectionalLight(0xffffff, 0.3);
      fill1.position.set(-3, 2, 2);
      this.scene.add(fill1);

      const fill2 = new THREE.DirectionalLight(0xffffff, 0.3);
      fill2.position.set(3, 2, 2);
      this.scene.add(fill2);
    },

    loadModel() {
      const loader = new GLTFLoader();
      this.loadingProgress = 10;

      loader.load(
        this.modelUrl,
        (gltf) => {
          this.onModelLoaded(gltf);
        },
        (progress) => {
          if (progress.total > 0) {
            const percent = (progress.loaded / progress.total) * 80;
            this.loadingProgress = Math.min(10 + percent, 90);
          }
        },
        (error) => {
          console.error('模型加载失败:', error);
          this.statusText = '模型加载失败';
          this.statusClass = 'error';
          this.statusIcon = '❌';
          this.isLoading = false;
        }
      );
    },

    onModelLoaded(gltf) {
      this.loadingProgress = 95;
      this.avatar = gltf.scene;
      
      // 计算并调整模型
      const box = new THREE.Box3().setFromObject(this.avatar);
      const center = box.getCenter(new THREE.Vector3());
      const size = box.getSize(new THREE.Vector3());

      this.avatar.position.x = -center.x;
      this.avatar.position.y = -box.min.y;
      this.avatar.position.z = -center.z;

      const maxDim = Math.max(size.x, size.y, size.z);
      const scale = 2 / maxDim;
      this.avatar.scale.set(scale, scale, scale);

      this.avatar.traverse((child) => {
        if (child.isMesh) {
          child.castShadow = true;
          child.receiveShadow = true;
          
          // 检测混合变形
          if (child.morphTargetInfluences && child.morphTargetDictionary) {
            this.morphTargets = child;
            console.log('✅ 找到混合变形目标:', Object.keys(child.morphTargetDictionary));
          }
        }
      });

      this.scene.add(this.avatar);
      this.collectBones();
      this.processMorphTargets();

      // 处理内置动画
      if (gltf.animations && gltf.animations.length > 0) {
        console.log('✅ 找到内置动画:', gltf.animations.length);
        this.mixer = new THREE.AnimationMixer(this.avatar);
        gltf.animations.forEach((clip) => {
          this.animations[clip.name] = this.mixer.clipAction(clip);
        });
      }

      this.loadingProgress = 100;
      setTimeout(() => {
        this.isLoading = false;
        this.statusText = '准备就绪';
        this.statusClass = 'success';
        this.statusIcon = '✓';
      }, 300);
    },

    // 收集所有骨骼
    collectBones() {
      console.log('🔍 开始扫描骨骼结构...');
      
      const boneMap = {
        'Head': ['Head', 'head'],
        'Neck': ['Neck', 'neck'],
        'Spine': ['Spine', 'spine'],
        'LeftArm': ['LeftArm', 'Left_Arm', 'leftarm', 'LeftForeArm'],
        'RightArm': ['RightArm', 'Right_Arm', 'rightarm', 'RightForeArm'],
        'LeftHand': ['LeftHand', 'Left_Hand', 'lefthand'],
        'RightHand': ['RightHand', 'Right_Hand', 'righthand'],
        'LeftEye': ['LeftEye', 'Left_Eye', 'lefteye'],
        'RightEye': ['RightEye', 'Right_Eye', 'righteye'],
        'Jaw': ['Jaw', 'jaw', 'jawbone', 'Chin', 'chin']
      };

      this.avatar.traverse((object) => {
        if (object.isBone || object.type === 'Bone') {
          const name = object.name;
          console.log('  🦴 发现骨骼:', name);

          // 匹配骨骼
          for (const [key, patterns] of Object.entries(boneMap)) {
            for (const pattern of patterns) {
              if (name === pattern) {
                this.bones[key] = object;
                this.foundBones[key] = object;
                console.log(`    ✓ 匹配成功: ${key} = ${name}`);
                break;
              }
            }
          }
        }
      });

      console.log('✅ 骨骼扫描完成');
      console.log('找到的骨骼:', this.foundBones);
    },

    // 处理混合变形
    processMorphTargets() {
      if (!this.morphTargets) {
        console.log('⚠️ 未找到混合变形，将使用骨骼动画');
        return;
      }

      const dict = this.morphTargets.morphTargetDictionary;
      console.log('混合变形列表:', dict);
    },

    // 切换程序化动画
    changeProceduralAnimation() {
      this.animationTime = 0;
      this.statusText = `切换到: ${this.animationLabels[this.proceduralAnimation]}`;
      this.statusClass = 'info';
      this.statusIcon = '🎬';
    },

    // 更新程序化动画
    updateProceduralAnimation(deltaTime) {
      if (!this.avatar) return;

      this.animationTime += deltaTime;
      const time = this.animationTime;
      const intensity = parseFloat(this.animationIntensity);

      // 眨眼动画
      if (this.enableBlink) {
        this.blinkTimer += deltaTime;
        if (this.blinkTimer >= this.nextBlinkTime) {
          this.performBlink();
          this.blinkTimer = 0;
          this.nextBlinkTime = 2 + Math.random() * 4; // 2-6秒随机眨眼
        }
      }

      // 口型同步
      if (this.isSpeaking && this.enableLipSync) {
        // 随机口型变化，模拟说话
        this.lipSyncIntensity = 0.3 + Math.sin(time * 20) * 0.2 + Math.random() * 0.1;
        this.updateMouth(this.lipSyncIntensity);
      } else {
        this.lipSyncIntensity = Math.max(0, this.lipSyncIntensity - deltaTime * 3);
        this.updateMouth(this.lipSyncIntensity);
      }

      // 基础动作
      switch(this.proceduralAnimation) {
        case 'idle':
          // 自然呼吸
          if (this.bones.Spine) {
            this.bones.Spine.rotation.x = Math.sin(time * 1.5) * 0.02 * intensity;
          }
          if (this.bones.Head) {
            this.bones.Head.rotation.x = Math.sin(time * 1.2) * 0.03 * intensity;
            this.bones.Head.rotation.y = Math.sin(time * 0.8) * 0.02 * intensity;
          }
          break;

        case 'presenting':
          // 讲解姿态 - 右手抬起
          if (this.bones.RightArm) {
            this.bones.RightArm.rotation.x = -0.5 + Math.sin(time * 2) * 0.1 * intensity;
            this.bones.RightArm.rotation.z = 0.3 + Math.sin(time * 1.5) * 0.05 * intensity;
          }
          if (this.bones.Head) {
            this.bones.Head.rotation.x = Math.sin(time * 2) * 0.05 * intensity;
            this.bones.Head.rotation.y = Math.sin(time * 1.5) * 0.1 * intensity;
          }
          if (this.bones.Spine) {
            this.bones.Spine.rotation.y = Math.sin(time * 1.2) * 0.05 * intensity;
          }
          break;

        case 'thinking':
          // 思考 - 手托下巴
          if (this.bones.RightArm) {
            this.bones.RightArm.rotation.x = -1.2;
          }
          if (this.bones.Head) {
            this.bones.Head.rotation.x = 0.2 + Math.sin(time * 1) * 0.05 * intensity;
          }
          break;

        case 'greeting':
          // 打招呼 - 挥手
          if (this.bones.RightArm) {
            this.bones.RightArm.rotation.x = -1.5;
            this.bones.RightArm.rotation.z = 0.5 + Math.sin(time * 5) * 0.3 * intensity;
          }
          if (this.bones.RightHand) {
            this.bones.RightHand.rotation.z = Math.sin(time * 8) * 0.3 * intensity;
          }
          break;

        case 'nodding':
          // 点头
          if (this.bones.Head) {
            this.bones.Head.rotation.x = Math.sin(time * 2.5) * 0.25 * intensity;
          }
          break;
      }
    },

    // 更新嘴部动作
    updateMouth(intensity) {
      // 如果有混合变形
      if (this.morphTargets && this.morphTargets.morphTargetInfluences) {
        const dict = this.morphTargets.morphTargetDictionary;
        
        // 常见的嘴部混合变形名称
        const mouthShapes = ['mouthOpen', 'jawOpen', 'mouth_open', 'A', 'O'];
        
        for (const shape of mouthShapes) {
          if (dict[shape] !== undefined) {
            this.morphTargets.morphTargetInfluences[dict[shape]] = intensity;
            return;
          }
        }
      }
      
      // 如果没有混合变形，尝试使用下颚骨骼
      if (this.bones.Jaw) {
        this.bones.Jaw.rotation.x = intensity * 0.3;
      } else if (this.bones.Head) {
        // 最后尝试微调头部
        this.bones.Head.rotation.x += Math.sin(this.animationTime * 20) * intensity * 0.02;
      }
    },

    // 眨眼动作
    performBlink() {
      if (!this.morphTargets || !this.morphTargets.morphTargetInfluences) return;
      
      const dict = this.morphTargets.morphTargetDictionary;
      const blinkShapes = ['eyeBlinkLeft', 'eyeBlinkRight', 'blink'];
      
      // 快速眨眼动画
      const blinkDuration = 0.15;
      let elapsed = 0;
      
      const blinkInterval = setInterval(() => {
        elapsed += 0.016; // ~60fps
        const progress = elapsed / blinkDuration;
        
        if (progress < 0.5) {
          // 闭眼
          const closeAmount = progress * 2;
          for (const shape of blinkShapes) {
            if (dict[shape] !== undefined) {
              this.morphTargets.morphTargetInfluences[dict[shape]] = closeAmount;
            }
          }
        } else if (progress < 1) {
          // 睁眼
          const openAmount = 1 - ((progress - 0.5) * 2);
          for (const shape of blinkShapes) {
            if (dict[shape] !== undefined) {
              this.morphTargets.morphTargetInfluences[dict[shape]] = openAmount;
            }
          }
        } else {
          // 完成
          clearInterval(blinkInterval);
        }
      }, 16);
    },

    changeBackground() {
      const backgrounds = {
        'gradient1': '#667eea',
        'gradient2': '#4facfe',
        'gradient3': '#f093fb',
        'white': '#ffffff',
        'dark': '#2c3e50'
      };
      this.renderer.setClearColor(new THREE.Color(backgrounds[this.background]));
    },

    startSpeak() {
      if (!this.speechText.trim()) {
        this.statusText = '请输入讲解内容';
        this.statusClass = 'warning';
        this.statusIcon = '⚠️';
        return;
      }

      if (!('speechSynthesis' in window)) {
        this.statusText = '浏览器不支持语音功能';
        this.statusClass = 'error';
        this.statusIcon = '❌';
        return;
      }

      const utterance = new SpeechSynthesisUtterance(this.speechText);
      utterance.lang = 'zh-CN';
      utterance.rate = parseFloat(this.speechRate);
      utterance.pitch = 1.0;

      utterance.onstart = () => {
        this.isSpeaking = true;
        this.statusText = '正在讲解中...';
        this.statusClass = 'speaking';
        this.statusIcon = '🔊';
        
        // 切换到讲解姿态
        this.proceduralAnimation = 'presenting';
        this.changeProceduralAnimation();
      };

      utterance.onend = () => {
        this.isSpeaking = false;
        this.statusText = '讲解完成';
        this.statusClass = 'success';
        this.statusIcon = '✓';
        
        setTimeout(() => {
          this.proceduralAnimation = 'idle';
          this.changeProceduralAnimation();
        }, 500);
      };

      utterance.onerror = () => {
        this.isSpeaking = false;
        this.statusText = '语音播报失败';
        this.statusClass = 'error';
        this.statusIcon = '❌';
      };

      speechSynthesis.speak(utterance);
    },

    stopSpeak() {
      if ('speechSynthesis' in window) {
        speechSynthesis.cancel();
        this.isSpeaking = false;
        this.statusText = '已停止讲解';
        this.statusClass = 'warning';
        this.statusIcon = '⏸';
        
        this.proceduralAnimation = 'idle';
        this.changeProceduralAnimation();
      }
    },

    onResize() {
      const container = this.$refs.avatarCanvas;
      if (!container || !this.camera || !this.renderer) return;

      const width = container.clientWidth;
      const height = container.clientHeight;

      this.camera.aspect = width / height;
      this.camera.updateProjectionMatrix();
      this.renderer.setSize(width, height);
    },

    animate() {
      requestAnimationFrame(this.animate);

      const delta = this.clock.getDelta();
      
      if (this.mixer) {
        this.mixer.update(delta);
      }

      this.updateProceduralAnimation(delta);

      if (this.renderer && this.scene && this.camera) {
        this.renderer.render(this.scene, this.camera);
      }
    },

    cleanup() {
      window.removeEventListener('resize', this.onResize);
      
      if (this.renderer) {
        this.renderer.dispose();
      }
      
      if (this.scene) {
        this.scene.traverse((object) => {
          if (object.geometry) {
            object.geometry.dispose();
          }
          if (object.material) {
            if (Array.isArray(object.material)) {
              object.material.forEach(material => material.dispose());
            } else {
              object.material.dispose();
            }
          }
        });
      }
      
      this.stopSpeak();
    }
  }
};
</script>

<style scoped>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

.digital-human-wrapper {
  display: flex;
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  overflow: hidden;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

/* 左侧控制面板 */
.control-panel {
  width: 500px;
  background: white;
  display: flex;
  flex-direction: column;
  box-shadow: 2px 0 20px rgba(0, 0, 0, 0.1);
  z-index: 10;
}

.panel-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 30px 25px;
}

.panel-header h2 {
  font-size: 24px;
  font-weight: 600;
  margin: 0;
}

.panel-content {
  flex: 1;
  padding: 25px;
  overflow-y: auto;
}

/* 调试信息框 */
.debug-box {
  background: #f8f9fa;
  padding: 15px;
  border-radius: 10px;
  border: 2px solid #e9ecef;
}

.debug-content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  font-size: 13px;
}

.debug-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 10px;
  background: white;
  border-radius: 5px;
}

.bone-name {
  color: #495057;
  font-weight: 500;
}

.bone-status {
  font-weight: 600;
}

.bone-status.found {
  color: #28a745;
}

.bone-status.missing {
  color: #dc3545;
}

/* 表单组 */
.form-group {
  margin-bottom: 25px;
}

.form-group label {
  display: block;
  color: #333;
  font-weight: 600;
  margin-bottom: 10px;
  font-size: 15px;
}

.form-group select,
.form-group textarea {
  width: 100%;
  padding: 12px 15px;
  border: 2px solid #e0e0e0;
  border-radius: 10px;
  font-size: 14px;
  font-family: inherit;
  transition: all 0.3s;
  background: white;
}

.form-group select:focus,
.form-group textarea:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
}

.form-group textarea {
  resize: vertical;
  min-height: 90px;
  line-height: 1.6;
}

/* 开关 */
.switch-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.switch {
  position: relative;
  display: inline-block;
  width: 50px;
  height: 26px;
}

.switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.switch-slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: #ccc;
  transition: 0.4s;
  border-radius: 26px;
}

.switch-slider:before {
  position: absolute;
  content: "";
  height: 20px;
  width: 20px;
  left: 3px;
  bottom: 3px;
  background-color: white;
  transition: 0.4s;
  border-radius: 50%;
}

input:checked + .switch-slider {
  background-color: #667eea;
}

input:checked + .switch-slider:before {
  transform: translateX(24px);
}

/* 滑块 */
.slider {
  width: calc(100% - 60px);
  height: 6px;
  border-radius: 3px;
  background: #e0e0e0;
  outline: none;
  -webkit-appearance: none;
  margin-right: 10px;
}

.slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #667eea;
  cursor: pointer;
  transition: all 0.3s;
}

.slider::-webkit-slider-thumb:hover {
  transform: scale(1.2);
  background: #764ba2;
}

.slider-value {
  display: inline-block;
  min-width: 45px;
  color: #667eea;
  font-weight: 600;
}

/* 按钮组 */
.button-group {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

button {
  flex: 1;
  padding: 14px 20px;
  border: none;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.4);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.btn-danger {
  background: linear-gradient(135deg, #f56c6c 0%, #f45454 100%);
  color: white;
}

.btn-danger:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(245, 108, 108, 0.4);
}

.btn-danger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

/* 状态栏 */
.status-bar {
  display: flex;
  align-items: center;
  padding: 15px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 500;
  gap: 10px;
}

.status-icon {
  font-size: 18px;
}

.status-text {
  flex: 1;
}

.status-bar.info {
  background: #e3f2fd;
  color: #1976d2;
}

.status-bar.success {
  background: #e8f5e9;
  color: #388e3c;
}

.status-bar.speaking {
  background: #fff3e0;
  color: #f57c00;
  animation: pulse 1.5s infinite;
}

.status-bar.warning {
  background: #fff3e0;
  color: #f57c00;
}

.status-bar.error {
  background: #ffebee;
  color: #d32f2f;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.85; }
}

/* 右侧3D展示区域 */
.avatar-container {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px;
}

.avatar-canvas {
  width: 100%;
  height: 100%;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.model-info {
  position: absolute;
  bottom: 50px;
  left: 50px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  padding: 15px 20px;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 5px 0;
  font-size: 14px;
}

.info-item .label {
  color: #666;
  font-weight: 500;
}

.info-item .value {
  color: #333;
  font-weight: 600;
}

.info-item .value.success {
  color: #388e3c;
}

/* 加载遮罩 */
.loading-mask {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  backdrop-filter: blur(5px);
}

.loading-content {
  text-align: center;
  color: white;
  max-width: 300px;
}

.spinner {
  width: 70px;
  height: 70px;
  border: 5px solid rgba(255, 255, 255, 0.2);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 25px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-text {
  font-size: 18px;
  margin: 15px 0;
  font-weight: 500;
}

.progress-bar {
  width: 100%;
  height: 8px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 4px;
  overflow: hidden;
  margin: 15px 0;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #667eea, #764ba2);
  border-radius: 4px;
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 16px;
  opacity: 0.9;
  font-weight: 600;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .control-panel {
    width: 420px;
  }
}

@media (max-width: 768px) {
  .digital-human-wrapper {
    flex-direction: column;
  }

  .control-panel {
    width: 100%;
    max-height: 50vh;
  }

  .avatar-container {
    flex: 1;
    padding: 15px;
  }

  .model-info {
    bottom: 20px;
    left: 20px;
    right: 20px;
  }
}

/* 滚动条样式 */
.panel-content::-webkit-scrollbar {
  width: 6px;
}

.panel-content::-webkit-scrollbar-track {
  background: #f1f1f1;
}

.panel-content::-webkit-scrollbar-thumb {
  background: #667eea;
  border-radius: 3px;
}

.panel-content::-webkit-scrollbar-thumb:hover {
  background: #764ba2;
}
</style>