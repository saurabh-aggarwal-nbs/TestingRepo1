def stepsForParallel = [:]
def parallelBatches = [:]
def stepsForSharedflows = [:]
def orgConfigRepo = null
def envConfigRepo = null
def deploymentConfig = ""
def baseLineVersion = "Release Baseline master"
def globalTokens = []
def envTokens = []
def baselineFile = "baseline-${env.APPLICATION_NAME}.json"
def releaseTag = env.RELEASE_SOURCE
def artefactList = ""
def deployToEnv = null
def PROCEED = true

pipeline {
    agent {
        any
    }

    stages {
        stage("Checkout Pipeline Code") {
            steps {
                checkout scm
            }
        }

        stage("Clean workspace") {
            steps {
                cleanWs cleanWhenAborted: false, cleanWhenFailure: false, cleanWhenNotBuilt: false, cleanWhenUnstable: false
            }
        }
    }
}
