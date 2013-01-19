import java.util.concurrent.*;
import java.util.*;
import java.io.*;
import org.yaml.snakeyaml.Yaml;

public class MessagePasser
{
	private String conf_filename;
	private String local_name;
	private int id = 0;
	ConcurrentLinkedQueue<Message> receive_queue = new ConcurrentLinkedQueue<Message>();
	ConcurrentLinkedQueue<Message> send_queue = new ConcurrentLinkedQueue<Message>();
	ConcurrentLinkedQueue<Message> delay_send_queue = new ConcurrentLinkedQueue<Message>();
	private HashMap<String, HashMap<String, Object> > users = new HashMap<String, HashMap<String, Object> >();
	private ArrayList<Rule> SendRules = new ArrayList<Rule>();
	private ArrayList<Rule> ReceiveRules = new ArrayList<Rule>();

	public MessagePasser(String conf_filename, String local_name)
	{
		this.conf_filename = conf_filename;
		this.local_name = local_name;
	}

	/* A helper function used in init(), or when the configuration file is modified */
	public void load_config()
	{
		users.clear();
		SendRules.clear();
		ReceiveRules.clear();

		FileInputStream fis = null;
		try
		{
			fis = new FileInputStream(conf_filename);
			Yaml yaml = new Yaml();
			Map<String, Object> data = (Map<String, Object>)yaml.load(fis);
			ArrayList<HashMap<String, Object> > config = (ArrayList<HashMap<String, Object> >)data.get("Configuration");
			for(HashMap<String, Object> mm : config)
			{
				String Name = (String)mm.get("Name");
				users.put(Name, mm);
			}
			if(!users.containsKey(local_name))
			{
				System.err.println("local_name: " + local_name + " isn't in " + conf_filename + ", please check again!");
				System.exit(1);
			}
			ArrayList<HashMap<String, Object> > send_rule_arr = (ArrayList<HashMap<String, Object> >)data.get("SendRules");
			
			System.out.println("send_rule_arr: ");
			System.out.println(send_rule_arr);
			
			for(HashMap<String, Object> mm : send_rule_arr)
			{
				String action = (String)mm.get("Action");
				Rule r = new Rule(action);
				for(String key: mm.keySet())
				{
					if(key.equals("Src"))
						r.setSrc((String)mm.get(key));
					if(key.equals("Dest"))
						r.setDest((String)mm.get(key));
					if(key.equals("Kind"))
						r.setKind((String)mm.get(key));
					if(key.equals("ID"))
						r.setId((Integer)mm.get(key));
					if(key.equals("Nth"))
						r.setNth((Integer)mm.get(key));
					if(key.equals("EveryNth"))
						r.setEveryNth((Integer)mm.get(key));
				}
				SendRules.add(r);
			}

			System.out.println("SendRules: ");
			System.out.println(SendRules);
			
			ArrayList<HashMap<String, Object> > receive_rule_arr = (ArrayList<HashMap<String, Object> >)data.get("ReceiveRules");
			for(HashMap<String, Object> mm : receive_rule_arr)
			{
				String action = (String)mm.get("Action");
				Rule r = new Rule(action);
				for(String key: mm.keySet())
				{
					if(key.equals("Src"))
						r.setSrc((String)mm.get(key));
					if(key.equals("Dest"))
						r.setDest((String)mm.get(key));
					if(key.equals("Kind"))
						r.setKind((String)mm.get(key));
					if(key.equals("ID"))
						r.setId((Integer)mm.get(key));
					if(key.equals("Nth"))
						r.setNth((Integer)mm.get(key));
					if(key.equals("EveryNth"))
						r.setEveryNth((Integer)mm.get(key));
				}
				ReceiveRules.add(r);
			}
//			System.out.println(users);
//			System.out.println(SendRules);
//			System.out.println(ReceiveRules);
		}
		catch(Exception ex)
		{
			ex.printStackTrace();
		}
		finally
		{
			try
			{
				if(fis != null)fis.close();
			}
			catch(IOException ioe)
			{
				ioe.printStackTrace();
			}
		}
	}

	public void init()
	{
		load_config();
//		set_server_side_connection(); // each node starts listening on the port
	}

	public Message receive()
	{
		Message r_cq = receive_queue.poll(); // if ConcurrentLinkedQueue is empty, cq.poll return null; cq.poll() is atomic operation
		return r_cq;
	}

	public void send(Message message)
	{
		message.set_id(++id);
		Rule matched_rule = CheckRule(message, 0); // check message with send rules
		if(matched_rule != null) // matches an action, do somethng
		{
			if(matched_rule.getAction().equals("drop")) // drop action
				; // drop it, i.e. do nothing
			else if(matched_rule.getAction().equals("duplicate")) // duplicate action
			{
				matched_rule.addMatch(); // as handout indicated, duplicated msg also matches the rule once!
				send_queue.add(message);
				while(!delay_send_queue.isEmpty())
				{
					send_queue.add(delay_send_queue.poll());
				}
				Message new_m = message.deepCopy();
				new_m.set_id(++id);
				send_queue.add(new_m);
			}
			else if(matched_rule.getAction().equals("delay"))
			{
				delay_send_queue.add(message);
			}
		}
		else  // message doesn't match any rule, add it to send_queue, and after that also need to check whether delay queue is empty or not
		{
			send_queue.add(message);
			while(!delay_send_queue.isEmpty())
			{
				send_queue.add(delay_send_queue.poll());
			}
		}
		
		for(Rule rr: SendRules)
		{
			System.out.println(rr);
		}
		System.out.println("send_queue:");
		for(Message m: send_queue)
		{
			System.out.println(m);
		}
		System.out.println("delay_send_queue:");
		for(Message m: delay_send_queue)
		{
			System.out.println(m);
		}
	}

	/** A helper function that checks whether or not a message matches a rule. 
	 @param type: 0 - check message with SendRules; 1 - check message with ReceiveRules
	 @return action name of the rule which this message matches with; or null if this message matches with no action.
	 */
	public Rule CheckRule(Message message, int type)
	{
		ArrayList<Rule> rule_arr = null;
		if(type == 0)
			rule_arr = SendRules;
		else if(type == 1)
			rule_arr = ReceiveRules;
		else
		{
			System.err.println("error use of CheckRule with type = " + type);
			System.exit(1);
		}
		for(Rule rule: rule_arr)
		{
			if((rule.getSrc() != null) && !(rule.getSrc().equals(message.getSrc())))
				continue;//not match, check next rule
			else if((rule.getDest() != null) && !(rule.getDest().equals(message.getDest())))
				continue;
			else if((rule.getKind() != null) && !(rule.getKind().equals(message.getKind())))
				continue;
			else if( (rule.getId() > 0) && (rule.getId() != message.getId()) )
				continue;

			rule.addMatch(); // already matched rule!

			if((rule.getNth() > 0) && (rule.getNth() != rule.getMatched()) )
				continue;
			else if( (rule.getEveryNth() > 0) && (rule.getMatched() % rule.getEveryNth()) != 0)
				continue;
			
			return rule;  // match this rule
		}
		return null;  // if no rules match, return null
	}

/*	public static void main(String[] args)
	{
		MessagePasser mp = new MessagePasser("config.yaml", "alice");
		mp.load_config();
	}*/
}
