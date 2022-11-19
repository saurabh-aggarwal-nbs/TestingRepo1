def doingExactClone = false
def exactClone = "EXACT CLONE"
def selectedComponents = []
def sourceEnvDeployments = []
String ENVIRONMENT_NAMES = "dev,test,pre,prd"
def deploymentBranch = "main"
def deploymentEnv = "main"
def environmentDeploymentConfigs = [:]

pipeline {
    agent any
    parameters {

        string(defaultValue: "dev", description: 'set env example: dev,uat', name: 'ENVIRONMENT')

        choice(name: 'ENVIRONMENT', choices: ENVIRONMENT_NAMES, description: 'Select an environment for deployment')

        extendedChoice (description: 'Select an environment for deployment', multiSelectDelimiter: ',',
                name: 'ENVIRONMENT', quoteValue: false, defaultValue: "", saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT',
                value: ENVIRONMENT_NAMES, visibleItemCount: 50)
    }
    stages {

        stage("Identify changes"){
            steps {
                script {

                    println "${env} -  - -- - -envs/${env.ENVIRONMENT}.json"
                    def envFiles = findFiles(glob: "envs/${env.ENVIRONMENT}.json")
                    def environmentConfigs=[]
                    envFiles.each { tenantFile ->
                        def commonCfg = readJSON file: "envs/config.json"
                        def desiredDeployment = merge(commonCfg, readJSON(file: tenantFile.path))
                        deploymentEnv = desiredDeployment.environment

                        def baseline = readJSON file: "deploymentdetails/baseline.json"
                        def envReleases = baseline.deploymentEnv
                        def components = identifyTenantDeployment(desiredDeployment.components, envReleases)

                        def tenantDeploymentConfig = [
                                "components": []
                        ]
                        if (desiredDeployment.components != null && desiredDeployment.components.size() > 0) {
                            def batchIndex = 0
                            def index = 0
                            desiredDeployment.components.findAll { c -> c.action != "none" }.each {  component ->
                                tenantDeploymentConfig.components << component
                            }
                        }
                        environmentConfigs << tenantDeploymentConfig
                    }
                    environmentDeploymentConfigs[deploymentEnv] = environmentConfigs
                    println "environmentDeploymentConfigs has data: ${environmentDeploymentConfigs}"

                }
            }
        }




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