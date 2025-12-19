<template>
  <div class="digital-human-wrapper">
    <!-- 左侧控制面板 -->
    <div class="control-panel">
      <div class="panel-header">
        <h2>AI数字人</h2>
      </div>

      <div class="panel-content">
        <!-- 混合变形检测 -->
        <div class="form-group debug-box">ss
          <label>🎨 混合变形检测 (Morph Targets)</label>
          <div class="debug-content">
            <div v-if="morphTargetsList.length === 0" class="no-morph">
              ⚠️ 未找到混合变形
            </div>
            <div v-else>
              <div class="morph-count">
                找到 <strong>{{ morphTargetsList.length }}</strong> 个混合变形
              </div>
              <div class="morph-scroll">
                <div v-for="(name, index) in morphTargetsList" :key="index" class="morph-item">
                  {{ name }}
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 骨骼检测 -->
        <div class="form-group debug-box">
          <label>🦴 骨骼检测</label>
          <div class="debug-content-grid">
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
            <option value="happy">微笑</option>
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

        <!-- 表情强度 -->
        <div class="form-group">
          <label>🎚️ 表情强度</label>
          <input 
            type="range" 
            v-model="expressionIntensity" 
            min="0.1" 
            max="2" 
            step="0.1"
            class="slider">
          <span class="slider-value">{{ expressionIntensity }}x</span>
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

        <!-- 测试按钮 -->
        <div class="test-buttons">
          <button @click="testMouth" class="btn-test">测试张嘴</button>
          <button @click="testSmile" class="btn-test">测试微笑</button>
        </div>

        <!-- 模型信息提示 -->
        <div class="info-box">
          <div class="info-title">ℹ️ 模型信息</div>
          <div class="info-text">
            当前模型只支持嘴部动画（张嘴和微笑），不包含眨眼混合变形。
          </div>
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
          <span class="label">混合变形:</span>
          <span class="value">{{ morphTargetsList.length }} 个</span>
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
  name: 'DigitalHumanRPM',
  
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
      
      // Ready Player Me 混合变形 - 只保留嘴部
      morphTargetsList: [],
      morphIndices: {
        // 嘴部混合变形
        mouthOpen: { mesh: null, index: -1 },
        mouthSmile: { mesh: null, index: -1 }
      },
      
      // 存储所有包含混合变形的网格
      morphMeshes: [],
      
      // 找到的骨骼
      foundBones: {
        'Head': null,
        'Neck': null,
        'Spine': null,
        'LeftArm': null,
        'RightArm': null,
        'LeftHand': null,
        'RightHand': null
      },
      
      // 动画控制
      proceduralAnimation: 'idle',
      expressionIntensity: 1.0,
      animationTime: 0,
      enableLipSync: true,
      lipSyncIntensity: 0,
      
      // UI状态
      background: 'gradient1',
      speechText: '大家好！我是数字人助手。我可以通过面部混合变形实现自然的说话和微笑动作。让我们开始精彩的讲解吧！',
      speechRate: 0.9,
      isSpeaking: false,
      isLoading: true,
      loadingText: '正在加载模型...',
      loadingProgress: 0,
      statusText: '系统初始化中',
      statusClass: 'info',
      statusIcon: 'ℹ️',
      
      // 模型URL
      modelUrl: '../../../static/model/4.glb'
    };
  },
  
  mounted() {
    this.init3D();
  },
  
  beforeDestroy() {
    this.cleanup();
  },
  
  methods: {
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
      console.log('========================================');
      console.log('✅ Ready Player Me 模型加载成功');
      console.log('========================================');
      
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

      // 扫描混合变形
      console.log('\n🔍 扫描混合变形 (Morph Targets)...');
      
      this.avatar.traverse((child) => {
        if (child.isMesh) {
          child.castShadow = true;
          child.receiveShadow = true;
          
          // 检查混合变形
          if (child.morphTargetInfluences && child.morphTargetDictionary) {
            console.log('\n✅ 网格:', child.name);
            
            const dict = child.morphTargetDictionary;
            const morphNames = Object.keys(dict);
            
            console.log('   混合变形:', morphNames.join(', '));
            
            // 存储这个网格
            this.morphMeshes.push({
              mesh: child,
              name: child.name,
              dictionary: dict
            });
            
            // 查找并映射混合变形
            if (dict['mouthOpen'] !== undefined) {
              if (this.morphIndices.mouthOpen.index === -1) {
                this.morphIndices.mouthOpen = {
                  mesh: child,
                  index: dict['mouthOpen']
                };
                console.log('   ✓ 映射 mouthOpen, 索引:', dict['mouthOpen']);
              }
            }
            
            if (dict['mouthSmile'] !== undefined) {
              if (this.morphIndices.mouthSmile.index === -1) {
                this.morphIndices.mouthSmile = {
                  mesh: child,
                  index: dict['mouthSmile']
                };
                console.log('   ✓ 映射 mouthSmile, 索引:', dict['mouthSmile']);
              }
            }
            
            // 记录所有混合变形名称（用于UI显示）
            morphNames.forEach(name => {
              const fullName = child.name + '.' + name;
              if (!this.morphTargetsList.includes(fullName)) {
                this.morphTargetsList.push(fullName);
              }
            });
          }
        }
      });

      console.log('\n📊 混合变形统计:');
      console.log('   包含混合变形的网格:', this.morphMeshes.length, '个');
      console.log('   混合变形总数:', this.morphTargetsList.length, '个');
      
      console.log('\n🎯 映射结果:');
      console.log('─'.repeat(60));
      let foundCount = 0;
      Object.keys(this.morphIndices).forEach((key) => {
        const morph = this.morphIndices[key];
        if (morph.mesh && morph.index !== -1) {
          console.log(`   ✓ ${key}: 网格="${morph.mesh.name}", 索引=${morph.index}`);
          foundCount++;
        } else {
          console.log(`   ✗ ${key}: 未找到`);
        }
      });
      console.log('─'.repeat(60));

      this.scene.add(this.avatar);
      this.collectBones();

      // 处理内置动画
      if (gltf.animations && gltf.animations.length > 0) {
        console.log('\n✅ 找到内置动画:', gltf.animations.length);
        this.mixer = new THREE.AnimationMixer(this.avatar);
        gltf.animations.forEach((clip) => {
          this.animations[clip.name] = this.mixer.clipAction(clip);
        });
      }

      this.loadingProgress = 100;
      const self = this;
      setTimeout(() => {
        self.isLoading = false;
        self.statusText = `准备就绪 - 支持 ${foundCount} 种面部表情`;
        self.statusClass = 'success';
        self.statusIcon = '✓';
      }, 300);
      
      console.log('\n========================================');
    },

    collectBones() {
      console.log('\n🦴 扫描骨骼结构...');
      
      const boneMap = {
        'Head': ['Head', 'head'],
        'Neck': ['Neck', 'neck'],
        'Spine': ['Spine', 'spine', 'Spine2'],
        'LeftArm': ['LeftArm', 'Left_Arm', 'leftarm', 'LeftForeArm'],
        'RightArm': ['RightArm', 'Right_Arm', 'rightarm', 'RightForeArm'],
        'LeftHand': ['LeftHand', 'Left_Hand', 'lefthand'],
        'RightHand': ['RightHand', 'Right_Hand', 'righthand']
      };

      const self = this;
      this.avatar.traverse(function(object) {
        if (object.isBone || object.type === 'Bone') {
          const name = object.name;

          Object.keys(boneMap).forEach(function(key) {
            const patterns = boneMap[key];
            for (let i = 0; i < patterns.length; i++) {
              const pattern = patterns[i];
              if (name === pattern) {
                self.bones[key] = object;
                self.foundBones[key] = object;
                console.log('   ✓', key, '=', name);
                break;
              }
            }
          });
        }
      });
    },

    changeProceduralAnimation() {
      this.animationTime = 0;
      this.statusText = '切换姿态';
      this.statusClass = 'info';
      this.statusIcon = '🎬';
    },

    updateProceduralAnimation(deltaTime) {
      if (!this.avatar) return;

      this.animationTime += deltaTime;
      const time = this.animationTime;
      const intensity = parseFloat(this.expressionIntensity);

      // 口型同步
      if (this.isSpeaking && this.enableLipSync) {
        this.lipSyncIntensity = 0.3 + Math.sin(time * 20) * 0.2 + Math.random() * 0.15;
        this.updateMouth(this.lipSyncIntensity);
      } else {
        this.lipSyncIntensity = Math.max(0, this.lipSyncIntensity - deltaTime * 3);
        this.updateMouth(this.lipSyncIntensity);
      }

      // 表情
      this.updateExpression(time, intensity);

      // 姿态动作
      this.updatePose(time, intensity);
    },

    updateMouth(intensity) {
      // 更新所有网格的嘴部混合变形
      this.morphMeshes.forEach(meshData => {
        const dict = meshData.dictionary;
        const mesh = meshData.mesh;
        
        if (dict['mouthOpen'] !== undefined) {
          mesh.morphTargetInfluences[dict['mouthOpen']] = intensity * 0.8;
        }
      });
    },

    updateSmile(intensity) {
      // 更新所有网格的微笑混合变形
      this.morphMeshes.forEach(meshData => {
        const dict = meshData.dictionary;
        const mesh = meshData.mesh;
        
        if (dict['mouthSmile'] !== undefined) {
          mesh.morphTargetInfluences[dict['mouthSmile']] = intensity;
        }
      });
    },

    updateExpression(time, intensity) {
      switch(this.proceduralAnimation) {
        case 'happy':
          this.updateSmile(0.6 * intensity);
          break;
        default:
          this.updateSmile(0);
          break;
      }
    },

    updatePose(time, intensity) {
      switch(this.proceduralAnimation) {
        case 'idle':
          if (this.bones.Spine) {
            this.bones.Spine.rotation.x = Math.sin(time * 1.5) * 0.02 * intensity;
          }
          if (this.bones.Head) {
            this.bones.Head.rotation.x = Math.sin(time * 1.2) * 0.03 * intensity;
            this.bones.Head.rotation.y = Math.sin(time * 0.8) * 0.02 * intensity;
          }
          break;

        case 'presenting':
          if (this.bones.RightArm) {
            this.bones.RightArm.rotation.x = -0.5 + Math.sin(time * 2) * 0.1 * intensity;
            this.bones.RightArm.rotation.z = 0.3 + Math.sin(time * 1.5) * 0.05 * intensity;
          }
          if (this.bones.Head) {
            this.bones.Head.rotation.x = Math.sin(time * 2) * 0.05 * intensity;
            this.bones.Head.rotation.y = Math.sin(time * 1.5) * 0.1 * intensity;
          }
          break;

        case 'thinking':
          if (this.bones.RightArm) {
            this.bones.RightArm.rotation.x = -1.2;
          }
          if (this.bones.Head) {
            this.bones.Head.rotation.x = 0.2;
          }
          break;

        case 'greeting':
          if (this.bones.RightArm) {
            this.bones.RightArm.rotation.x = -1.5;
            this.bones.RightArm.rotation.z = 0.5 + Math.sin(time * 5) * 0.3 * intensity;
          }
          break;
      }
    },

    testMouth() {
      console.log('🧪 测试张嘴功能...');
      console.log('========================================');
      
      const mouthOpen = this.morphIndices.mouthOpen;
      console.log('mouthOpen 状态:');
      console.log('  - 网格:', mouthOpen.mesh ? mouthOpen.mesh.name : '未找到');
      console.log('  - 索引:', mouthOpen.index);
      console.log('  - 可用:', mouthOpen.mesh && mouthOpen.index !== -1 ? '✓' : '✗');
      console.log('========================================');
      
      this.updateMouth(0.8);
      const self = this;
      setTimeout(() => {
        self.updateMouth(0);
      }, 500);
    },

    testSmile() {
      console.log('🧪 测试微笑功能...');
      console.log('========================================');
      
      const mouthSmile = this.morphIndices.mouthSmile;
      console.log('mouthSmile 状态:');
      console.log('  - 网格:', mouthSmile.mesh ? mouthSmile.mesh.name : '未找到');
      console.log('  - 索引:', mouthSmile.index);
      console.log('  - 可用:', mouthSmile.mesh && mouthSmile.index !== -1 ? '✓' : '✗');
      console.log('========================================');
      
      this.updateSmile(1.0);
      const self = this;
      setTimeout(() => {
        self.updateSmile(0);
      }, 1000);
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

      const self = this;
      utterance.onstart = function() {
        self.isSpeaking = true;
        self.statusText = '正在讲解中...';
        self.statusClass = 'speaking';
        self.statusIcon = '🔊';
        
        self.proceduralAnimation = 'presenting';
      };

      utterance.onend = function() {
        self.isSpeaking = false;
        self.statusText = '讲解完成';
        self.statusClass = 'success';
        self.statusIcon = '✓';
        
        setTimeout(() => {
          self.proceduralAnimation = 'idle';
        }, 500);
      };

      utterance.onerror = function() {
        self.isSpeaking = false;
        self.statusText = '语音播报失败';
        self.statusClass = 'error';
        self.statusIcon = '❌';
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
        const self = this;
        this.scene.traverse(function(object) {
          if (object.geometry) {
            object.geometry.dispose();
          }
          if (object.material) {
            if (Array.isArray(object.material)) {
              object.material.forEach(function(material) {
                material.dispose();
              });
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
/* 样式保持不变 */
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
  font-size: 22px;
  font-weight: 600;
  margin: 0;
}

.panel-content {
  flex: 1;
  padding: 25px;
  overflow-y: auto;
}

.debug-box {
  background: #f8f9fa;
  padding: 15px;
  border-radius: 10px;
  border: 2px solid #e9ecef;
  margin-bottom: 20px;
}

.debug-content {
  font-size: 13px;
  color: #666;
}

.no-morph {
  color: #f57c00;
  font-weight: 500;
  padding: 10px;
  background: #fff3e0;
  border-radius: 5px;
}

.morph-count {
  color: #388e3c;
  font-weight: 600;
  margin-bottom: 10px;
  font-size: 14px;
}

.morph-scroll {
  max-height: 150px;
  overflow-y: auto;
  background: white;
  border-radius: 5px;
  padding: 10px;
}

.morph-item {
  padding: 4px 8px;
  background: #e3f2fd;
  color: #1976d2;
  border-radius: 4px;
  margin: 3px 0;
  font-size: 12px;
  font-family: 'Courier New', monospace;
}

.debug-content-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
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
  font-size: 12px;
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

.form-group {
  margin-bottom: 20px;
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
  min-height: 80px;
  line-height: 1.6;
}

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

.button-group {
  display: flex;
  gap: 12px;
  margin-bottom: 15px;
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
}

.test-buttons {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 15px;
}

.btn-test {
  padding: 10px 15px;
  background: #e3f2fd;
  color: #1976d2;
  border: 2px solid #90caf9;
  font-size: 13px;
}

.btn-test:hover {
  background: #bbdefb;
  transform: translateY(-1px);
}

.info-box {
  background: #e3f2fd;
  border: 2px solid #90caf9;
  border-radius: 10px;
  padding: 15px;
  margin-bottom: 15px;
}

.info-title {
  font-weight: 600;
  color: #1565c0;
  margin-bottom: 8px;
  font-size: 14px;
}

.info-text {
  color: #1976d2;
  font-size: 13px;
  line-height: 1.5;
}

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
}

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
</style>