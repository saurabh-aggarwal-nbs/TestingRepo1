import java.text.SimpleDateFormat
import groovy.json.*
import java.time.LocalDateTime

def doingExactClone = false
def exactClone = "EXACT CLONE"
def selectedComponents = []
def sourceEnvDeployments = []
String ENVIRONMENT_NAMES = "dev,test,pre,prd"
def deploymentBranch = "main"
def deploymentEnv = "main"
def environmentDeploymentConfigs = [:]
def deployedComponents = [:]
def plannedComponents = [:]

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

                    println "envs/${env.ENVIRONMENT}.json"
                    def environmentConfigs = []
                    def desiredDeployment = readJSON file: "envs/${env.ENVIRONMENT}.json"
                    deploymentEnv = desiredDeployment.environment

//                    def baseline = readJSON file: "deploymentdetails/baseline.json"
                    println "Checkout baseline repo"
                    def repositoryName = "https://github.com/saurabh-aggarwal-nbs"
                    def baselineRepo = readJSON text: "{'name':'baseline', 'branch': 'main'}"
//                    dir("gitops"){
                        checkoutRepository(baselineRepo)
//                    }
                    def baseline = readJSON file: "${deploymentEnv}-baseline.json"
                    println "baseline ${baseline}"

                    def envReleases = baseline.deploymentEnv
                    def components = identifyTenantDeployment(desiredDeployment.components, envReleases)

                    def tenantDeploymentConfig = [
                            "components": []
                    ]
                    if (desiredDeployment.components != null && desiredDeployment.components.size() > 0) {
                        def batchIndex = 0
                        def index = 0
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
                        stage("Deploying environment ${envDeploymentConfigs.key}") {
                            deployedComponents[envDeploymentConfigs.key] = [:]
                            envDeploymentConfigs.value.each { deploymentConfigItem ->
                                stage("${deploymentConfigItem}") {
                                    deployedComponents[envDeploymentConfigs.key] = []
                                    deploymentConfigItem.components.each { component ->
                                        dir("${component.name}") {
                                            deployArtifact(component)
                                            if (component.deployed) {
                                                component.commit = component.checkoutInfo.GIT_COMMIT
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

    post {
        always {
            script  {
                println "Generating artifacts to store what deployment happened"
                if(env.ENVIRONMENT){
                    environmentDeploymentConfigs.each { envDeploymentConfigs ->
                        envDeploymentConfigs.value.each { deploymentConfigItem ->
                            plannedComponents=[]
                            deploymentConfigItem.components.each { component ->
                                plannedComponents << component
                            }
                        }
                        writeJSON json: deployedComponents[envDeploymentConfigs.key]?:[:], file: "output/deployed-components-${envDeploymentConfigs.key}.json", pretty: 1
                        writeJSON json: plannedComponents, file: "checkoutdir/${envDeploymentConfigs.key}-baseline.json", pretty: 1

                    }
                    updateBaselineFile()
                }
            }
//            archiveArtifacts(allowEmptyArchive: true, artifacts: 'output/*.json', followSymlinks: false)
//            cleanWs cleanWhenAborted: false, cleanWhenFailure: false, cleanWhenNotBuilt: false, cleanWhenUnstable: false
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

def identifyTenantDeployment(components, envReleases){
    println "components has data: ${components}   and envReleases has data :${envReleases}"

    def componentsToDeploy = []
    components.each { component ->
        def trackingEntryName = component.name
        def envReleaseEntry = envReleases.find {it.name == trackingEntryName }
        println "envReleaseEntry has data: ${envReleaseEntry} "

        if(envReleaseEntry) {
            def envComponentDeployment = envReleaseEntry.value

//            def envComponentDeployment = new JsonSlurper().parseText(envReleaseEntry.value)
            if((component.tag && component.tag != envComponentDeployment.tag)
                    || (component.commit && component.commit != envComponentDeployment.commit)
                    || (!component.tag && !component.commit && component.branch == envComponentDeployment.branch)
                    || (component.branch != envComponentDeployment.branch)
            )
            {
                component.action = "upgrade"
                component.fromTag = envComponentDeployment.tag
                component.fromCommit = envComponentDeployment.commit
                component.fromBranch = envComponentDeployment.branch
                component.fromGlobalConfigKey = envComponentDeployment.globalConfigKey
            }
            else {
                component.action = "none"
                component.fromTag = envComponentDeployment.tag
                component.fromCommit = envComponentDeployment.commit
                component.fromBranch = envComponentDeployment.branch
                component.fromGlobalConfigKey = envComponentDeployment.globalConfigKey
            }
        }
        else {
            // new deployment
            component.action = "install"
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

    if(component.checkoutInfo.GIT_COMMIT == component.fromCommit)
    {
        println "Commit ${component.checkoutInfo.GIT_COMMIT} is already deployed, skipping deployment for ${component.name} (${deploymentConfig.environment})"
        component.deployed = false
        return
    }

    def date = new Date()
    def sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
    def deployedOn =sdf.format(date)

    component.deployeedOn = deployedOn
    component.trackDeployment = true

    def trackingEntry = [
            "deployedOn": deployedOn,
            "tag": component.tag,
            "commit": component.commit ?: component.checkoutInfo.GIT_COMMIT,
            "branch": component.branch ?: component.checkoutInfo.GIT_BRANCH,
    ]



    println "deploying component ${component.name}"


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


def updateBaselineFile(){

    dir("checkoutdir") {
        checkout([$class: 'GitSCM', branches: [[name: 'main']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 0, noTags: true, reference: '', shallow: false, timeout: 60], [$class: 'CheckoutOption', timeout: 60]], submoduleCfg: [],
                  userRemoteConfigs: [[credentialsId: 'saurabh-aggarwal-nbs', url: 'https://github.com/saurabh-aggarwal-nbs/baseline.git']]])
//        writeJSON json: plannedComponents, file: "${env.ENVIRONMENT}-baseline.json", pretty: 4
    }
    println "pushing the changes now"

    def branch = env.BUILD_TAG
    // Push the changes
    sh "ssh-agent bash -c \" \
                cd checkoutdir; \
                git config --global user.email jenkins@test.com; \
                git config --global user.name saurabh-aggarwal-nbs; \
                git checkout -b ${branch}; \
                git add ${env.ENVIRONMENT}-baseline.json; \
                git commit -m 'updating baseline repo'; \
                git push origin ${branch}\""
    println "pushing the changes completed"


}