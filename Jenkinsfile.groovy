import java.text.SimpleDateFormat
import groovy.json.*
import java.time.LocalDateTime

String ENVIRONMENT_NAMES = "dev,test,pre,prd"
def deploymentEnv = ""
def environmentDeploymentConfigs = [:]
def deployedComponents = [:]
def plannedComponents = [:]
def updatedBaselineComponents = [:]
def finalFile = "checkoutdir/${env.ENVIRONMENT}-baseline.json"

pipeline {
    agent any
    parameters {

//        choice(name: 'ENVIRONMENT', choices: ENVIRONMENT_NAMES, description: 'Select an environment for deployment')
        extendedChoice (description: 'Select an environment for deployment', multiSelectDelimiter: ',',
                name: 'ENVIRONMENT', quoteValue: false, defaultValue: "", saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT',
                value: ENVIRONMENT_NAMES, visibleItemCount: 50)
    }
    stages {

        stage("Identify changes") {
            when {
                expression {
                    env.ENVIRONMENT
                }
            }
            steps {
                script {

                    def environmentConfigs = []
                    def desiredDeployment = readJSON file: "envs/${env.ENVIRONMENT}.json"
                    deploymentEnv = desiredDeployment.environment

                    println "Checkout baseline repo"
                    dir("checkoutdir") {
                        def repositoryName = "https://github.com/saurabh-aggarwal-nbs"
                        def baselineRepo = readJSON text: "{'name':'baseline', 'branch': 'main'}"
                        checkoutRepository(baselineRepo)
                    }

                    def baseline = [:]
                    if(fileExists("checkoutdir/${deploymentEnv}-baseline.json")){
                        baseline = []
                        baseline = readJSON file: "checkoutdir/${deploymentEnv}-baseline.json"
                    }
                    println "baseline ${baseline}"

                    def components = identifyTenantDeployment(desiredDeployment.components, baseline)

                    def tenantDeploymentConfig = [
                            "components": []
                    ]
                    if (desiredDeployment.components != null && desiredDeployment.components.size() > 0) {
                        desiredDeployment.components.findAll { c -> c.action != "none" }.each { component ->
                            tenantDeploymentConfig.components << component
                        }
                    }
                    environmentConfigs << tenantDeploymentConfig
                    environmentDeploymentConfigs[deploymentEnv] = environmentConfigs
                    println "environmentDeploymentConfigs has data: ${environmentDeploymentConfigs}"

                }
            }
        }

        stage("Deploy") {
            when {
                expression {
                    env.ENVIRONMENT
                }
            }
            steps {
                script {
                    environmentDeploymentConfigs.each { envDeploymentConfigs ->
                        stage("Deploying environment ${env.ENVIRONMENT}") {
                            deployedComponents[envDeploymentConfigs.key] = [:]
                            envDeploymentConfigs.value.each { deploymentConfigItem ->
                                stage("${deploymentConfigItem}") {
                                    deployedComponents[envDeploymentConfigs.key] = []
                                    deploymentConfigItem.components.each { component ->
                                        dir("${component.name}") {
                                            stage("${component.name}"){
                                                deployArtifact(component)
                                                if (component.deployed) {
                                                    deployedComponents[envDeploymentConfigs.key] << component
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    post {
        always {
            script  {
                println "Generating artifacts to store what deployment happened"
                if(env.ENVIRONMENT){
                    environmentDeploymentConfigs.each { envDeploymentConfigs ->
                        envDeploymentConfigs.value.each { deploymentConfigItem ->
                            plannedComponents=[]
                            updatedBaselineComponents = []
                            deploymentConfigItem.components.each { component ->
                                println "Generating component with tracking ${component} and ${component.trackingEntry}"

                                plannedComponents << component
                                updatedBaselineComponents << component.trackingEntry
                            }
                        }
                        writeJSON json: deployedComponents[envDeploymentConfigs.key]?:[:], file: "output/deployed-components-${envDeploymentConfigs.key}.json", pretty: 1
                        writeJSON json: plannedComponents, file: "output/planned-components-${envDeploymentConfigs.key}.json", pretty: 1
                        writeJSON json: updatedBaselineComponents, file: "output/${env.ENVIRONMENT}-baseline.json", pretty: 1

                    }
                    def map1 = readJSON file: "output/${env.ENVIRONMENT}-baseline.json"
                    def map2 = readJSON file: "${finalFile}"
                    if(map1 != map2){
                        println "deployment component tracking required"
                        sh """
                            cp 'output/${env.ENVIRONMENT}-baseline.json' ${finalFile}
                        """
                        updateBaselineFile(finalFile)
                    }
                }
            }
            archiveArtifacts(allowEmptyArchive: true, artifacts: 'output/*.json', followSymlinks: false)
            cleanWs cleanWhenAborted: false, cleanWhenFailure: false, cleanWhenNotBuilt: false, cleanWhenUnstable: false
        }
        success {
            println "Successful"
        }
        unstable {
            println "Unstable"
        }
        failure {
            println "Failed"
        }
    }
}

def identifyTenantDeployment(components, baseline){
    println "components has data: ${components}  and baseline has data :${baseline}"

    def componentsToDeploy = []

    components.each { component ->
        component.action = "install"
        baseline.each { envComponentDeployment ->

            if (envComponentDeployment.name == component.name) {
                println "baselineComp got this data: ${envComponentDeployment}"
                println "component got this data: ${component}"

                if ((component.tag && component.tag != envComponentDeployment.tag)
                        || (component.commit && component.commit != envComponentDeployment.commit)
                        || (!component.tag && !component.commit && component.branch == envComponentDeployment.branch)
                        || (component.branch != envComponentDeployment.branch)
                ) {
                    component.action = "upgrade"
                    component.fromTag = envComponentDeployment.tag
                    component.fromCommit = envComponentDeployment.commit
                    component.fromBranch = envComponentDeployment.branch
                } else {
                    component.action = "none"
                    component.fromTag = envComponentDeployment.tag
                    component.fromCommit = envComponentDeployment.commit
                    component.fromBranch = envComponentDeployment.branch
                }
            }
        }
        componentsToDeploy << component
    }
    return componentsToDeploy
}

def deployArtifact(component) {
    def envTokens = []
    def repoGlobalTokens = []
    println "checking out " + component.name
    component.checkoutInfo = checkoutRepository(component)
    def trackingEntry = [:]
//    def date = new Date()
//    def sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
//    def deployedOn =sdf.format(date)
//    component.deployeedOn = deployedOn
//    trackingEntry.deployedOn =  deployedOn
    trackingEntry.tag = component.tag
    trackingEntry.commit = component.commit?component.commit:component.checkoutInfo.GIT_COMMIT
    trackingEntry.name = component.name
    trackingEntry.branch =  component.branch

    if(component.checkoutInfo.GIT_COMMIT == component.fromCommit)
    {
        println "Commit ${component.checkoutInfo.GIT_COMMIT} is already deployed, skipping deployment for ${component.name}"
        component.deployed = false
//        trackingEntry.deployedOn =  deployedOn
        component.trackingEntry = trackingEntry
        return
    }

    component.trackDeployment = true
    component.trackingEntry = trackingEntry

    println "deploying component ${component}"


    /// do your deployment step here

    component.deployed = true
}

def checkoutRepository(repository) {
    def checkoutName = "refs/heads/${repository.branch}"
    if(repository.tag){
        checkoutName = "refs/tags/${repository.tag}"
        println "Checking out from tag ${checkoutName}"
    }
    else if(repository.commit)
    {
        checkoutName = "${repository.commit}"
        println "Checking out from commit ${checkoutName}"
    }
    else
    {
        println "Checking out from branch ${checkoutName}"
    }
    println "Repository name : ${repository.name}"
    def checkoutInfo = checkout([
            $class: 'GitSCM',
            branches: [[name: checkoutName]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [],
            submoduleCfg: [],
            userRemoteConfigs: [[
                                        credentialsId: 'saurabh-aggarwal-nbs',
                                        url: "https://github.com/saurabh-aggarwal-nbs/${repository.name}.git/"
                                ]]
    ])
    return checkoutInfo
}

def updateBaselineFile(finalFile){
    sh "ssh-agent bash -c \" \
                cd checkoutdir; \
                git config --global user.email jenkins@test.com; \
                git config --global user.name saurabh-aggarwal-nbs; \
                git add ${finalFile}; \
                git status; \
                git commit -am 'updating baseline ${finalFile}'; \
                git push origin HEAD:main\""
    println "pushing the changes completed"
}