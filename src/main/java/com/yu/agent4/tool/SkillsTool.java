package com.yu.agent4.tool;

import com.yu.agent4.tool.utils.Skills;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 构建Skill的工具
 */
@Component
public class SkillsTool {
    private static final String TOOL_DESCRIPTION_TEMPLATE = """
			Execute a skill within the main conversation

			<skills_instructions>
			When users ask you to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities and domain knowledge.

			How to use skills:
			- Invoke skills using this tool with the skill name only (no arguments)
			- When you invoke a skill, you will see <command-message>The "{name}" skill is loading</command-message>
			- The skill's prompt will expand and provide detailed instructions on how to complete the task

			NOTE: Response always starts start with the base directory of the skill execution environment. You can use this to retrieve additional files of call shell commands.
			Skill description follows after the base directory line.

			Important:
			- Only use skills listed in <available_skills> below
			- Do not invoke a skill that is already running
			</skills_instructions>

			<available_skills>
			%s
			</available_skills>
			""";

    //创建输入参数
    public static record SkillInput(@ToolParam(description = "The skill name (no arguments). E.g., \"pdf\" or \"xlsx\"") String command){}
    //创建调用函数
    public static class SkillFunction implements Function<SkillInput, String> {
        /**
         * 初始化有的Skill
         */

        private Map<String , Skill> skillMap;
        public SkillFunction(Map<String , Skill> skillMap) {
            this.skillMap = skillMap;
        }

        @Override
        public String apply(SkillInput skillInput) {
            String command = skillInput.command;
            Skill skill = skillMap.get(command);
            if (skill != null) {
                return "Base directory for this skill: %s\n\n%s".formatted(skill.basePath(), skill.content());
            }

            return "Skill not found: " + skillInput.command();

        }
    }
    public static Builder builder(){
        return new Builder();
    }

    //创建Builder


    public static class Builder{
        //skill的路径是什么

        private List<Skill> skills = new ArrayList<>();
        private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;

        protected Builder() {

        }

        public Builder toolDescriptionTemplate(String template) {
            this.toolDescriptionTemplate = template;
            return this;
        }

        public Builder addSkillsResources(List<Resource> skillsResources) {
            this.skills.addAll(Skills.loadResources(skillsResources));
            return this;
        }

        public Builder addSkillsResource(Resource skillsResource) {
            this.skills.addAll(Skills.loadResource(skillsResource));
            return this;
        }

        public Builder addSkillsDirectory(String skillsRootDirectory) {
            this.addSkillsDirectories(List.of(skillsRootDirectory));
            return this;
        }

        public Builder addSkillsDirectories(List<String> skillsRootDirectories) {
            for (String skillsRootDirectory : skillsRootDirectories) {
                this.skills.addAll(Skills.loadDirectory(skillsRootDirectory));
            }
            return this;
        }

        public ToolCallback build(){
            String skillsXml = this.skills.stream().map(s -> s.toXML()).collect(Collectors.joining("\n"));
            return FunctionToolCallback.builder("Skill",new SkillFunction(toSkillMap(skills)))
                    .description(toolDescriptionTemplate.formatted(skillsXml))
                    .inputType(SkillInput.class)
                    .build();
        }
    }

    public static record Skill(String basePath, Map<String, Object> forMatter, String content){
        /**
         * 获取skill的名字
         * @return
         */
        public String name (){
            return forMatter.get("name").toString();
        }

        /**
         * 输出xml
         *
         * @return
         */
        public String toXML(){
            String frontMatterXml = this.forMatter()
                    .entrySet()
                    .stream()
                    .map(e -> "  <%s>%s</%s>".formatted(e.getKey(), e.getValue(), e.getKey()))
                    .collect(Collectors.joining("\n"));

            return "<skill>\n%s\n</skill>".formatted(frontMatterXml);
        }
    }

    /**
     * 获取SkillList
     * @param skills
     * @return
     */
    public static Map<String, Skill> toSkillMap(List<Skill> skills){
        Map<String, Skill> skillMap = new HashMap<>();

        for(Skill skill : skills){
            skillMap.put(skill.name(), skill);
        }
        return skillMap;
    }

}
