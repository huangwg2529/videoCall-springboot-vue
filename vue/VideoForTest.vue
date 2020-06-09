<template>
    <div>
    <div class="container">
            <div class="row">
                <div class="col-md-5">
                    <br /> <br /> <label class="control-label" for="peer">Peer</label>
                    <div class="row">
                        <div class="col-md-6">
                            <b-button  variant="primary" class="text-center" @click="call">test</b-button>
                            <b-button  variant="primary" class="text-center" @click="stop">stop</b-button>
                        </div>
                    </div>
                    <br /> <label class="control-label" for="console">Console</label><br>
                    <br>
                    <div id="console" class="democonsole">
                        <ul></ul>
                    </div>
                </div>
                <div class="col-md-7">
                    <div id="videoBig">
                        <video id="videoOutput" autoplay width="640px" height="480px"></video>
                    </div>
                    <div id="videoSmall">
                        <video id="videoInput" autoplay width="240px" height="180px"
                            poster="../assets/logo.png"></video>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
export default {
    name: 'video',
    data() {
        return{
            callerID: 1000025,
            calleeID: 1000026,
        }
    },
    computed : {
        input: {
            get: function() {
                return document.getElementById('videoInput');
            }
        },
        output: {
            get: function() {
                return document.getElementById('videoOutput');
            }
        }
    },

    mounted() {
        this.$wssApi.setInputAndOutput(this.input, this.output);
    },
    methods: {
        call() {
            console.log("click call");
            this.$wssApi.call(this.callerID, this.calleeID);
        },
        stop() {
            this.$wssApi.stop();
        }
    }
}
</script>

<style scoped>
 @import '../assets/video.css'
</style>